package org.jetbrains.plugins.scala.caches

import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiComment, PsiElement}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter
import org.jetbrains.plugins.scala.extensions._
import org.junit.Assert

class BlockModCountTest extends ScalaLightCodeInsightFixtureTestAdapter {
  //symbolic names are chosen to keep test data readable
  private val | = "/*caret*/"
  private val ^^ = "/*shouldChange*/"
  private val ~~ = "/*shouldNotChange*/"

  override def runInDispatchThread() = false

  override def loadScalaLibrary = false

  protected def doTest(fileText: String): Unit = {
    val fileName = getTestName(true) + ".scala"
    myFixture.configureByText(fileName, fileText.withNormalizedSeparator.trim)

    val caretOffsets = collectMarkerOffsets(|)
    val offsetsToChange = collectMarkerOffsets(^^)
    val offsetsToStay = collectMarkerOffsets(~~)

    Assert.assertTrue("No caret markers found", caretOffsets.nonEmpty)
    Assert.assertTrue("No modCount markers found", offsetsToChange.size + offsetsToStay.size > 0)

    for {
      caretOffset <- caretOffsets
    } {

      val (countsToChangeBefore, countsToStayBefore) =
        (computeModCounts(offsetsToChange), computeModCounts(offsetsToStay))

      changePsiAt(caretOffset)

      val (countsToChangeAfter, countsToStayAfter) =
        (computeModCounts(offsetsToChange), computeModCounts(offsetsToStay))

      countsToChangeBefore.zip(countsToChangeAfter).zip(offsetsToChange).foreach {
        case ((before, after), offset) =>
          Assert.assertTrue(message("Modification count should change", caretOffset, offset), before != after)
      }

      countsToStayBefore.zip(countsToStayAfter).zip(offsetsToStay).foreach {
        case ((before, after), offset) =>
          Assert.assertTrue(message("Modification count should not change", caretOffset, offset), before == after)
      }

    }
  }

  private def assertError(fileText: String): Unit = {
    try {
      doTest(fileText)
      throw new RuntimeException
    } catch {
      case _: AssertionError => //expected
    }
  }

  private def message(description: String, caretOffset: Int, psiOffset: Int) = {
    val elementAtCaret = getFile.findElementAt(caretOffset)
    val psiElement = getFile.findElementAt(psiOffset)
    s"""$description at `${psiElement.getText}` in `${lineText(psiOffset)}`
       |if there was change at `${elementAtCaret.getText}` in `${lineText(caretOffset)}`""".stripMargin
  }

  private def lineText(offset: Int): String = {
    val document = getEditor.getDocument
    val line = document.getLineNumber(offset)
    val range = TextRange.create(document.getLineStartOffset(line), document.getLineEndOffset(line))
    document.getText(range)
  }

  private def modificationCount(element: PsiElement): Long =
    BlockModificationTracker(element).getModificationCount

  private def maxElementWithStartAt(offset: Int): PsiElement = {
    val leaf = getFile.findElementAt(offset)
    leaf.withParentsInFile.takeWhile(_.getTextRange.getStartOffset == offset).toSeq.last
  }

  private def collectMarkerOffsets(marker: String): Array[Int] = inReadAction {
    getFile.depthFirst().collect {
      case c: PsiComment if c.textMatches(marker) => c.getTextRange.getEndOffset
    }.toArray
  }

  private def computeModCounts(offsets: Seq[Int]): Array[Long] = inReadAction {
    offsets.map(maxElementWithStartAt).map(modificationCount).toArray
  }

  //SCL-15795
  def testForStmt(): Unit = doTest(
    s"""class ${~~}ForTests {
       |  ${~~}trait A {
       |    ${~~}def foo: String
       |  }
       |  ${~~}val file: A = ???
       |  ${^^}for {
       |    x <- ${|}Option(file.foo)
       |    ${|}y <- Option(file.foo)
       |    z = file.foo
       |  } yield x.toString + ${|}y.toString + ${^^}z.toString
       |}""".stripMargin)

  def testChangeInBodyWithExplicitTypeElement(): Unit = doTest(
    s"""
       |object Test ${~~}{
       |  def ${~~}foo: Unit = {
       |    ${|}println()
       |  }
       |
       |  def ${~~}fooz() {
       |    println(${|})
       |  }
       |
       |  def bar: ${~~}String = "${|}bar"
       |
       |  def baz: Int = {
       |    ${|}42
       |  }
       |
       |  val xx: Int = Test.${|}baz
       |}
       |""".stripMargin
  )

  def testChangeInBodyAffectsBody(): Unit = doTest(
    s"""
       |${~~}object Test {
       |  def ${~~}foo(): Int = {
       |    def ${|}local() = ""
       |
       |    ${^^}val ${|}x = 1
       |
       |    val y = 2
       |    var i = ${|}0
       |
       |    ${^^}while (i < y) {
       |      ${^^}i += 1
       |    }
       |
       |    ${|}x + y
       |  }
       |}
       |""".stripMargin)

  def testChangesNotAffectInferenceOutside(): Unit = doTest(
    s"""
       |class ${~~}Test {
       |
       |  println(${|}"call in constructor")
       |
       |  def foo(): ${~~}Int = {
       |
       |    while (true${|}) {
       |      ${|}println(1)
       |    }
       |
       |    do {
       |      println(${|}1)
       |    } while(true)
       |
       |    Test.${|}foo()
       |
       |    //some comment${|}
       |
       |    /* ${|}
       |      some multiline comment
       |    */
       |
       |    val ${~~}zz = try {
       |      "42".toInt
       |    } finally {
       |      ${|}println("finally")
       |    }
       |
       |
       |    val x = ${~~}1
       |    val y = 1
       |
       |    ${~~}x + y
       |  }
       |}
       |""".stripMargin)

   def testTopLevelChangesAffectsEverything(): Unit = doTest(
    s"""
      |${|}class ${|}Test ${|}{
      |
      |  ${^^}class ${|}A {
      |    def ${|}foo = ${|}"${^^}"
      |  }
      |
      |  ${^^}val a${|} = ${^^}new ${|}A {}
      |
      |  def ${|}bar(): String = {
      |
      |    def local() = ${^^}1
      |
      |    ${^^}"bar" + local(${^^})
      |  }
      |}
      |""".stripMargin)

  def testFailure(): Unit = {
    assertError(
      s"""
        |class A {
        |  def ${|}foo: ${~~}String = "foo"
        |}
        |""".stripMargin)
    assertError(
      s"""class A {
         |  def foo: ${^^}String = "${|}foo"
         |}
         |""".stripMargin)
  }
}