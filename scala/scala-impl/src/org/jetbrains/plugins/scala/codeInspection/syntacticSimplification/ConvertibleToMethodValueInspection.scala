package org.jetbrains.plugins.scala
package codeInspection.syntacticSimplification

import com.intellij.codeInspection.{ProblemHighlightType, ProblemsHolder}
import com.intellij.openapi.project.Project
import com.intellij.psi._
import org.jetbrains.plugins.scala.codeInspection.collections.MethodRepr
import org.jetbrains.plugins.scala.codeInspection.syntacticSimplification.ConvertibleToMethodValueInspection._
import org.jetbrains.plugins.scala.codeInspection.{AbstractFixOnPsiElement, AbstractInspection, InspectionBundle}
import org.jetbrains.plugins.scala.extensions.{&&, PsiElementExt, PsiModifierListOwnerExt, ResolvesTo, childOf}
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScConstructor, ScMethodLike}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.{createExpressionFromText, createExpressionWithContextFromText}
import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.psi.types.api.FunctionType
import org.jetbrains.plugins.scala.lang.psi.types.result._
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.project.ProjectPsiElementExt
import org.jetbrains.plugins.scala.util.KindProjectorUtil.PolymorphicLambda

/**
 * Nikolay.Tropin
 * 5/30/13
 */
object ConvertibleToMethodValueInspection {
  val inspectionName = InspectionBundle.message("convertible.to.method.value.name")
  val inspectionId = "ConvertibleToMethodValue"

  /**
    * Since kind-projector operates *before* typer, it can't analyse types of the
    * expressions used in rewrites and therefore requires arguments to value-level
    * lambdas to be identifiale as functions solely by shape (i.e. explicit match-cases
    * or anonymous functions, not method references).
    */
  private object ArgumentToPolymorphicLambda {
    def unapply(expr: ScExpression): Boolean =
      if (!expr.kindProjectorPluginEnabled) false
      else
        expr match {
          case childOf(_, childOf(_, ScMethodCall(PolymorphicLambda(_, _, _), _))) => true
          case _                                                                   => false
        }
  }
}

class ConvertibleToMethodValueInspection extends AbstractInspection(inspectionName) {

  override def actionFor(implicit holder: ProblemsHolder): PartialFunction[PsiElement, Any] = {
    case ArgumentToPolymorphicLambda() => () // disallowed by kind projector rules
    case MethodRepr(_, _, Some(ref), _)
      if ref.bind().exists(srr => srr.implicitType.nonEmpty || srr.implicitFunction.nonEmpty || hasByNameOrImplicitParam(srr.getElement)) =>
      //do nothing if implicits or by-name params are involved
    case MethodRepr(expr, qualOpt, Some(_), args) =>
      if (allArgsUnderscores(args) && qualOpt.forall(onlyStableValuesUsed))
        registerProblem(holder, expr, InspectionBundle.message("convertible.to.method.value.anonymous.hint"))
    case und: ScUnderscoreSection if und.bindingExpr.isDefined =>
      val isInParameterOfParameterizedClass = und.parentOfType(classOf[ScClassParameter])
        .exists(_.containingClass.hasTypeParameters)
      def mayReplace() = und.bindingExpr.get match {
        case ResolvesTo(fun) if hasByNameOrImplicitParam(fun) => false
        case ScReferenceExpression.withQualifier(qual) => onlyStableValuesUsed(qual)
        case _ => true
      }

      if (!isInParameterOfParameterizedClass && mayReplace())
        registerProblem(holder, und, InspectionBundle.message("convertible.to.method.value.eta.hint"))
  }

  private def allArgsUnderscores(args: Seq[ScExpression]): Boolean = {
    args.nonEmpty && args.forall(arg => arg.isInstanceOf[ScUnderscoreSection] && ScUnderScoreSectionUtil.isUnderscore(arg))
  }

  private def onlyStableValuesUsed(qual: ScExpression): Boolean = {
    def isStable(named: PsiNamedElement) = ScalaPsiUtil.nameContext(named) match {
      case cp: ScClassParameter => !cp.isVar
      case f: PsiField => f.hasFinalModifier
      case o: ScObject => o.isLocal || ScalaPsiUtil.hasStablePath(o)
      case _: PsiMethod | _: ScVariable => false
      case _ => true
    }

    qual.depthFirst(e => !e.isInstanceOf[ScImportStmt]).forall {
      case _: ScNewTemplateDefinition => false
      case (_: ScReferenceExpression | ScConstructor.byReference(_)) && ResolvesTo(named: PsiNamedElement) => isStable(named)
      case _ => true
    }
  }

  private def registerProblem(holder: ProblemsHolder, expr: ScExpression, hint: String) {
    possibleReplacements(expr).find(isSuitableForReplace(expr, _)).foreach { replacement =>
      holder.registerProblem(expr, inspectionName,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        new ConvertibleToMethodValueQuickFix(expr, replacement, hint))
    }
  }

  private def methodWithoutArgumentsText(expr: ScExpression): Seq[String] = expr match {
    case call: ScMethodCall => Seq(call.getEffectiveInvokedExpr.getText)
    case ScInfixExpr(_, oper, _) if !ScalaNamesUtil.isOperatorName(oper.refName) =>
      val infixCopy = expr.copy.asInstanceOf[ScInfixExpr]
      infixCopy.getNode.removeChild(infixCopy.right.getNode)
      Seq(infixCopy.getText)
    case und: ScUnderscoreSection => und.bindingExpr.map(_.getText).toSeq
    case _ => Seq.empty
  }

  private def isSuitableForReplace(oldExpr: ScExpression, newExprText: String): Boolean = {

    val newExpr = createExpressionWithContextFromText(newExprText, oldExpr.getContext, oldExpr)
    oldExpr.expectedType(fromUnderscore = false) match {
      case Some(expectedType) if FunctionType.isFunctionType(expectedType) =>
        def conformsExpected(expr: ScExpression): Boolean = expr.`type`().getOrAny conforms expectedType

        conformsExpected(oldExpr) && conformsExpected(newExpr) && oldExpr.`type`().getOrAny.conforms(newExpr.`type`().getOrNothing)
      case None if newExprText endsWith "_" =>
        (oldExpr.`type`(), newExpr.`type`()) match {
          case (Right(oldType), Right(newType)) => oldType.equiv(newType)
          case _ => false
        }
      case _ => false
    }
  }

  private def possibleReplacements(expr: ScExpression): Seq[String] = {
    val withoutArguments = methodWithoutArgumentsText(expr)
    val withUnderscore =
      if (expr.getText endsWith "_") Nil
      else withoutArguments.map(_ + " _")

    withoutArguments ++ withUnderscore
  }

  private def hasByNameOrImplicitParam(elem: PsiElement): Boolean = {
    elem match {
      case fun: ScMethodLike => fun.parameterList.params.exists(p => p.isCallByNameParameter || p.isImplicitParameter)
      case _ => false
    }
  }
}

class ConvertibleToMethodValueQuickFix(expr: ScExpression, replacement: String, hint: String)
  extends AbstractFixOnPsiElement(hint, expr) {

  override protected def doApplyFix(scExpr: ScExpression)
                                   (implicit project: Project): Unit = {
    val newExpr = createExpressionFromText(replacement)
    scExpr.replaceExpression(newExpr, removeParenthesis = true)
  }
}
