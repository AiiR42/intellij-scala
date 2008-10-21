package org.jetbrains.plugins.scala.lang.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction;
import org.jetbrains.plugins.scala.util.TestUtils;

/**
 * @author ven
 */
public class ResolveCallTest extends ScalaResolveTestCase {
  protected String getTestDataPath() {
    return TestUtils.getTestDataPath() + "/resolve/";
  }

  @Override
  protected boolean removeTestDataPath() {
    String name = getTestName(false);
    if (name.equals("ObjectApply")) return true;
    return false;
  }

  public void testObjectApply() throws Exception {
    PsiReference ref = configureByFile("call/objectApply.scala");
    PsiElement resolved = ref.resolve();
    assertTrue(resolved instanceof ScFunction);
    assertEquals("apply", ((ScFunction) resolved).getName());
  }
  public void testSuperConstructorInvocation() throws Exception {
    PsiReference ref = configureByFile("call/SuperConstructorInvocation.scala");
    PsiElement resolved = ref.resolve();
    assertTrue(resolved instanceof ScFunction);
    assertEquals("c", ((ScFunction) resolved).getContainingClass().getName());
  }

}
