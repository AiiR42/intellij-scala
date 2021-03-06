package org.jetbrains.plugins.scala
package debugger
package evaluateExpression

import org.junit.experimental.categories.Category

@Category(Array(classOf[DebuggerTests]))
class ScalaObjectEvaluationTest_2_11 extends ScalaObjectEvaluationTestBase {
  override protected def supportedIn(version: ScalaVersion) = version <= Scala_2_11
}

@Category(Array(classOf[DebuggerTests]))
class ScalaObjectEvaluationTest_2_12 extends ScalaObjectEvaluationTestBase {
  override protected def supportedIn(version: ScalaVersion) = version >= Scala_2_12
}

@Category(Array(classOf[DebuggerTests]))
abstract class ScalaObjectEvaluationTestBase extends ScalaDebuggerTestCase {
  addFileWithBreakpoints("SimpleObject.scala",
    s"""
       |object EvaluateObjects {
       |  def main(args: Array[String]) {
       |    ""$bp
       |  }
       |}
       """.stripMargin.trim()
  )
  addSourceFile("Simple.scala", "object Simple")
  addSourceFile("qual/Simple.scala",
    s"""
      |package qual
      |
      |object Simple
      """.stripMargin.trim()
  )
  addSourceFile("qual/SimpleCaseClass.scala",
    s"""
       |package qual
       |
       |case class SimpleCaseClass()
      """.stripMargin.trim()
  )
  addSourceFile("StableInner.scala",
    s"""
       |package qual
       |
       |object StableInner {
       |  object Inner
       |}
      """.stripMargin.trim()
  )
  addSourceFile("qual/ClassInner.scala",
    s"""
       |package qual
       |
       |class ClassInner {
       |  object Inner
       |}
      """.stripMargin.trim()
  )
  def testEvaluateObjects(): Unit = {
    runDebugger() {
      waitForBreakpoint()
      evalStartsWith("Simple", "Simple$")
      evalStartsWith("qual.Simple", "qual.Simple$")
      evalStartsWith("collection.JavaConversions", "scala.collection.JavaConversions$")
      evalEquals("qual.SimpleCaseClass", "SimpleCaseClass")
      evalStartsWith("qual.StableInner.Inner", "qual.StableInner$Inner$")
      evalStartsWith("val x = new qual.ClassInner(); x.Inner", "qual.ClassInner$Inner$")
    }
  }

  addFileWithBreakpoints("InnerClassObjectFromObject.scala",
    s"""
       |object InnerClassObjectFromObject {
       |  class S {
       |    object SS {
       |      object S {
       |        def foo() {
       |          SS.S //to have $$outer field
       |          ""$bp
       |        }
       |      }
       |      object G
       |    }
       |    def foo() {
       |      SS.S.foo()
       |    }
       |  }
       |
       |  def main(args: Array[String]) {
       |    val x = new S()
       |    x.foo()
       |  }
       |}
      """.stripMargin.trim()
  )
  def testInnerClassObjectFromObject(): Unit = {
    runDebugger() {
      waitForBreakpoint()
      evalStartsWith("SS.G", "InnerClassObjectFromObject$S$SS$G")
      evalStartsWith("SS.S", "InnerClassObjectFromObject$S$SS$S")
      evalStartsWith("S", "InnerClassObjectFromObject$S$SS$S")
      evalStartsWith("SS", "InnerClassObjectFromObject$S$SS$")
    }
  }
}