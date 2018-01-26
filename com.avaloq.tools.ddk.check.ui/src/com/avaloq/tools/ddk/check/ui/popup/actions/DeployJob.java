/*******************************************************************************
 * Copyright (c) 2016 Avaloq Evolution AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Avaloq Evolution AG - initial API and implementation
 *******************************************************************************/
package com.avaloq.tools.ddk.check.ui.popup.actions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IPathVariableManager;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.XtextResourceSet;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

import com.avaloq.tools.ddk.check.ui.Messages;
import com.avaloq.tools.ddk.check.ui.internal.Activator;
import com.avaloq.tools.ddk.checkcfg.CheckCfgConstants;
import com.avaloq.tools.ddk.checkcfg.checkcfg.CheckConfiguration;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;


/**
 * Job deploying the plugin bundle generated by the artifacts of the given project.
 * If a check configuration file is present, deploys it too.
 */
// CHECKSTYLE:OFF (data coupling 13 instead of maximum 10).
public class DeployJob extends Job {
  // CHECKSTYLE:ON

  private static final Logger LOGGER = Logger.getLogger(DeployJob.class);

  private static final String MANIFEST_MF = "MANIFEST.MF"; //$NON-NLS-1$
  private static final String META_INF = "META-INF"; //$NON-NLS-1$
  private static final Set<String> IGNORED_RESOURCES = ImmutableSet.of(".classpath", ".project", ".settings"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

  private final BundleContext bundleContext = ResourcesPlugin.getPlugin().getBundle().getBundleContext();
  private final IProject project;

  /**
   * Creates a new instance of {@link DeployJob}.
   *
   * @param name
   *          the name of the job
   * @param project
   *          the plugin project whose bundle should be deployed.
   */
  public DeployJob(final String name, final IProject project) {
    super(name);
    this.project = project;
  }

  @Override
  protected IStatus run(final IProgressMonitor monitor) {
    try {
      deployCheckBundle();
    } catch (DeployException e) {
      return new Status(Status.ERROR, Activator.getPluginId(), Messages.DeployJob_CouldNotDeployCheckBundle, e);
    }

    LOGGER.info(NLS.bind("Generated bundle from project {0} deployed.", project.getName())); //$NON-NLS-1$

    try {
      deployCheckConfiguration();
    } catch (DeployException e) {
      return new Status(Status.ERROR, Activator.getPluginId(), Messages.DeployJob_CannotDeployMoreThanOneCheckConfiguration, e);
    }

    LOGGER.info(NLS.bind("Check configuration for project {0} deployed.", project.getName())); //$NON-NLS-1$

    return Status.OK_STATUS;
  }

  /**
   * Generated a bundle from this project and deploys it (install and run).
   *
   * @throws DeployException
   *           a deployment exception.
   */
  private void deployCheckBundle() throws DeployException {

    File tempDir = createTempDirectory("check_catalog");
    tempDir.mkdirs();

    final File jar = generateJar(tempDir, new NullProgressMonitor());

    String bundleLocation;
    try {
      bundleLocation = jar.getCanonicalPath();
    } catch (IOException e) {
      LOGGER.error(Messages.DeployJob_CouldntGetBundleLocation, e);
      throw new DeployException(e);
    }

    Bundle managedBundle = Platform.getBundle(project.getName());
    if (managedBundle != null) {
      LOGGER.info(NLS.bind(Messages.DeployJob_BundleAlreadyDeployed, project.getName()));
      try {
        UndeployJob.undeployBundle(managedBundle);
      } catch (BundleException e) {
        LOGGER.error(NLS.bind(Messages.UndeployJob_FailedToStopAndUninstallBundleWithSymbolicName, managedBundle.getSymbolicName()), e);
        throw new DeployException(e);
      }
    }

    LOGGER.info(NLS.bind("Starting the bundle {0} generated from the project {1}", bundleLocation, project.getName())); //$NON-NLS-1$
    try {
      managedBundle = bundleContext.installBundle(bundleLocation, Files.asByteSource(jar).openStream());
      managedBundle.start();
    } catch (BundleException e) {
      LOGGER.error(Messages.DeployJob_FailedToInstallAndStartBundle, e);
      throw new DeployException(e);
    } catch (IOException e) {
      LOGGER.error(Messages.DeployJob_FailedToReadBundle, e);
      throw new DeployException(e);
    }

    if (!jar.delete()) {
      LOGGER.warn("Could not delete temp file."); //$NON-NLS-1$
    }
  }

  /**
   * Creates a new instance of {@link DeployJob}.
   *
   * @throws DeployException
   *           a general deployment exception.
   */
  private void deployCheckConfiguration() throws DeployException {

    List<IFile> checkCfgFiles = getCheckConfigurationFiles();
    if (checkCfgFiles.isEmpty()) {
      return;
    }
    if (checkCfgFiles.size() != 1) {
      showTooManyCheckConfigurationDialog();
      return;
    }
    IFile file = checkCfgFiles.get(0);

    IWorkspace workspace = ResourcesPlugin.getWorkspace();
    IPathVariableManager pathMan = workspace.getPathVariableManager();
    if (pathMan.validateName(CheckCfgConstants.CHECK_CFG_VAR_NAME).isOK() && pathMan.validateValue(file.getFullPath()).isOK()) {
      try {
        pathMan.setURIValue(CheckCfgConstants.CHECK_CFG_VAR_NAME, file.getLocationURI());
      } catch (CoreException e) {
        LOGGER.error(NLS.bind(Messages.DeployJob_CouldNotSetPathVariable, CheckCfgConstants.CHECK_CFG_VAR_NAME));
        throw new DeployException(e);
      }
    } else {
      LOGGER.error(NLS.bind(Messages.DeployJob_CouldNotSetPathVariable, CheckCfgConstants.CHECK_CFG_VAR_NAME));
      throw new DeployException();
    }

    XtextResourceSet rs = new XtextResourceSet();
    String uriString = pathMan.getURIValue(CheckCfgConstants.CHECK_CFG_VAR_NAME).toString();
    URI uri = URI.createURI(uriString, true);
    Resource resource = rs.createResource(uri);
    try {
      resource.load(new FileInputStream(new File(pathMan.getURIValue(CheckCfgConstants.CHECK_CFG_VAR_NAME))), rs.getLoadOptions());
      rs.getURIResourceMap().put(uri, resource);
      EcoreUtil.resolveAll(resource);
      CheckConfiguration checkConfig = (CheckConfiguration) resource.getContents().get(0);
      assert checkConfig != null;
    } catch (IOException e) {
      LOGGER.error(NLS.bind(Messages.DeployJob_ExceptionWhileReadingTheCheckConfigurationFile, uriString), e);
      throw new DeployException(e);
    }
  }

  /**
   * Fetches the check configuration files of this project.
   *
   * @return the list of configuration files, never {@code null}
   * @throws DeployException
   *           deploy exception
   */
  private List<IFile> getCheckConfigurationFiles() throws DeployException {
    final List<IFile> checkCfgFiles = new ArrayList<IFile>();
    try {
      project.accept(new IResourceVisitor() {
        @Override
        public boolean visit(final IResource resource) throws CoreException {
          if (resource instanceof IProject) {
            return true;
          }
          if (resource instanceof IFile && CheckCfgConstants.FILE_EXTENSION.equalsIgnoreCase(((IFile) resource).getFileExtension())) {
            checkCfgFiles.add((IFile) resource);
            return false;
          }
          if (isProjectJarIgnoreResource(resource)) {
            return false;
          }
          if (resource instanceof IFolder && "bin".equals(resource.getName())) {
            return false;
          }
          return true;
        }
      });
    } catch (CoreException e) {
      LOGGER.error(e.getMessage(), e);
      throw new DeployException(e);
    }
    return checkCfgFiles;
  }

  /**
   * Generate jar in the target folder of the project.
   *
   * @param tempDir
   *          the temp directory to store jar, must not be {@code null}
   * @param progressMonitor
   *          the progress monitor, must not be {@code null}
   * @return the file, never {@code null}
   * @throws DeployException
   *           a general deployment exception.
   */
  private File generateJar(final File tempDir, final IProgressMonitor progressMonitor) throws DeployException {

    try {
      buildProject();
    } catch (CoreException e) {
      LOGGER.error("Failed to build project", e); //$NON-NLS-1$
      throw new DeployException(e);
    }

    final List<IResource> resources = new ArrayList<IResource>();
    final List<IResource> manifest = new ArrayList<IResource>();
    try {
      project.accept(new IResourceVisitor() {
        @Override
        public boolean visit(final IResource resource) throws CoreException {
          if (resource instanceof IProject) {
            return true;
          }
          if (resource.getName().equals(META_INF)) {
            return true;
          }
          if (resource.getName().equals(MANIFEST_MF)) {
            manifest.add(resource);
            return false;
          }
          if (isProjectJarIgnoreResource(resource)) {
            return false;
          } else {
            resources.add(resource);
            return false;
          }
        }
      });
    } catch (CoreException e) {
      LOGGER.error(e.getMessage(), e);
      throw new DeployException(e);
    }
    JarPackageData description = new JarPackageData();
    IPath location = new Path(tempDir.toString()).append(project.getName() + ".jar"); //$NON-NLS-1$
    description.setJarLocation(location);
    final IPath manifestLocation = manifest.get(0).getFullPath();
    description.setManifestLocation(manifestLocation);
    description.setGenerateManifest(false);
    description.setSaveManifest(true);
    description.setElements(resources.toArray());
    IJarExportRunnable runnable = description.createJarExportRunnable(null);
    try {
      runnable.run(progressMonitor);
    } catch (InvocationTargetException e) {
      LOGGER.error(e.getMessage(), e);
      throw new DeployException(e);
    } catch (InterruptedException e) {
      LOGGER.error(e.getMessage(), e);
      throw new DeployException(e);
    }
    return new File(location.toOSString());
  }

  /**
   * Builds the project.
   *
   * @return the file
   * @throws CoreException
   *           the core exception
   */
  private File buildProject() throws CoreException {
    /*
     * Doing clean build takes too long with Xtend. With auto build even full build is not necessary,
     * but keep full build in case auto build is disabled. Then bin folder may be outdated or even empty.
     * project.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());
     */
    project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
    return new File(project.getWorkspace().getRoot().getFolder(getJavaOutputDirectory()).getLocation().toOSString());
  }

  /**
   * Checks whether the resource is ignored for this project (no need to pack into JAR).
   *
   * @param resource
   *          the resource
   * @return true, if is project jar ignore resource
   */
  private boolean isProjectJarIgnoreResource(final IResource resource) {
    if (IGNORED_RESOURCES.contains(resource.getName())) {
      return true;
    }
    return getJavaOutputDirectory().toOSString().equals(resource.getLocation().toOSString());
  }

  /**
   * Gets the java output directory of the given project.
   *
   * @return the java output directory
   */
  private IPath getJavaOutputDirectory() {
    IPath outputLocation = null;
    try {
      IJavaProject javaProject = JavaCore.create(project);
      if (javaProject != null) {
        outputLocation = javaProject.getOutputLocation();
      }
    } catch (JavaModelException e) {
      LOGGER.error("Failed to get java output of the project", e); //$NON-NLS-1$
      return null;
    }
    return outputLocation;
  }

  /**
   * Creates the temp directory.
   *
   * @param name
   *          the name of the directory, must not be {@code null}
   * @return the file, never {@code null}
   * @throws DeployException
   *           Signals that an I/O exception has occurred.
   */
  private static File createTempDirectory(final String name) throws DeployException {
    final File temp;
    try {
      temp = File.createTempFile(name, null);
    } catch (IOException e) {
      LOGGER.error(e.getMessage());
      throw new DeployException(e);
    }
    if (!(temp.delete())) {
      LOGGER.error(NLS.bind(Messages.DeployJob_CouldNotCreateTemporaryDirectoryForJar, temp.getAbsolutePath()));
      throw new DeployException();
    }
    if (!(temp.mkdir())) {
      LOGGER.error(NLS.bind(Messages.DeployJob_CouldNotCreateTemporaryDirectoryForJar, temp.getAbsolutePath()));
      throw new DeployException();
    }
    return temp;
  }

  /**
   * Shows an error dialog box informing the user that there are too many check configurations in the project.
   */
  private static void showTooManyCheckConfigurationDialog() {
    PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
      @Override
      public void run() {
        final Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
        final String dialogIitle = Messages.DeployJob_TooManyCheckConfigurations;
        final String dialogMessage = Messages.DeployJob_CannotDeployMoreThanOneCheckConfiguration;
        MessageDialog d = new MessageDialog(shell, dialogIitle, null, dialogMessage, MessageDialog.ERROR, new String[] {Messages.DeployJob_DialogOk}, 0);
        d.open();
      }
    });

  }

  /**
   * A custom exception for internal error handling.
   */
  private static class DeployException extends Exception {

    /** The serialVersionUID. */
    private static final long serialVersionUID = 5396061810970195802L;

    /** Constructor. */
    DeployException() {
      super();
    }

    /** Constructor with cause exception. */
    DeployException(final Exception e) {
      super(e);
    }

  }

}
