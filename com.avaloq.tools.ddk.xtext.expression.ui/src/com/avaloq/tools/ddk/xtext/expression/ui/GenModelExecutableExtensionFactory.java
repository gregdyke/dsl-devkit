/*
 * generated by Xtext
 */
package com.avaloq.tools.ddk.xtext.expression.ui;

import org.eclipse.xtext.ui.guice.AbstractGuiceAwareExecutableExtensionFactory;
import org.osgi.framework.Bundle;

import com.avaloq.tools.ddk.xtext.expression.ui.internal.Activator;
import com.avaloq.tools.ddk.xtext.expression.ui.internal.ExpressionActivator;
import com.google.inject.Injector;


/**
 * This class was generated. Customizations should only happen in a newly
 * introduced subclass.
 */
public class GenModelExecutableExtensionFactory extends AbstractGuiceAwareExecutableExtensionFactory {

  @Override
  protected Bundle getBundle() {
    return ExpressionActivator.getInstance().getBundle();
  }

  @Override
  protected Injector getInjector() {
    return ExpressionActivator.getInstance().getInjector(Activator.COM_AVALOQ_TOOLS_DDK_XTEXT_EXPRESSION_GENMODEL);
  }

}