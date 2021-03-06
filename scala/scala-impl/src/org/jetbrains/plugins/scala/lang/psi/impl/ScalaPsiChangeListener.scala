package org.jetbrains.plugins.scala.lang.psi.impl

import com.intellij.openapi.util.Key
import com.intellij.psi._
import org.jetbrains.plugins.scala.ScalaLanguage

object ScalaPsiChangeListener {

  def apply(onPsiChange: PsiElement => Unit,
            onPropertyChange: () => Unit): PsiTreeChangeListener = {

    new ScalaPsiChangeListenerImpl(onPsiChange, onPropertyChange, ScalaPsiEventFilter.defaultFilters)
  }

  private sealed trait EventType
  private object EventType {
    object ChildRemoved    extends EventType
    object ChildAdded      extends EventType
    object ChildMoved      extends EventType
    object ChildReplaced   extends EventType
    object ChildrenChanged extends EventType
  }
  import EventType._

  private class ScalaPsiChangeListenerImpl(onScalaPsiChange: PsiElement => Unit,
                                           onNonScalaChange: () => Unit,
                                           filters: Seq[ScalaPsiEventFilter])
    extends PsiTreeChangeAdapter {

    private def onPsiChangeEvent(event: PsiTreeChangeEvent,
                                 eventType: EventType): Unit = {
      if (filters.exists(_.shouldSkip(event)))
        return

      val element = extractChangedPsi(event, eventType)
      val validElement = validElementFor(element)

      if (validElement.getLanguage.isKindOf(ScalaLanguage.INSTANCE)) {
        if (!filters.exists(_.shouldSkip(element))) {
          onScalaPsiChange(validElement)
        }
      }
      else onNonScalaChange()
    }

    override def childRemoved(event: PsiTreeChangeEvent): Unit = onPsiChangeEvent(event, ChildRemoved)

    override def childReplaced(event: PsiTreeChangeEvent): Unit = onPsiChangeEvent(event, ChildReplaced)

    override def childAdded(event: PsiTreeChangeEvent): Unit = onPsiChangeEvent(event, ChildAdded)

    override def childrenChanged(event: PsiTreeChangeEvent): Unit = onPsiChangeEvent(event, ChildrenChanged)

    override def childMoved(event: PsiTreeChangeEvent): Unit = onPsiChangeEvent(event, ChildMoved)

    override def propertyChanged(event: PsiTreeChangeEvent): Unit = onNonScalaChange()
  }

  private def extractChangedPsi(event: PsiTreeChangeEvent, eventType: EventType): PsiElement = eventType match {
    case EventType.ChildRemoved    =>
      setValidElementFor(event.getChild, event.getParent)
      event.getChild
    case EventType.ChildAdded      => event.getChild
    case EventType.ChildMoved      => event.getChild
    case EventType.ChildReplaced   => event.getNewChild
    case EventType.ChildrenChanged => event.getParent
  }

  private val validElementKey: Key[PsiElement] = Key.create("scala.change.valid.element")
  private def setValidElementFor(element: PsiElement, parent: PsiElement): Unit = {
    element.putUserData(validElementKey, parent)
  }
  private def validElementFor(element: PsiElement): PsiElement =
    Option(element.getUserData(validElementKey)).getOrElse(element)
}
