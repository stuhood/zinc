package sbt
package internal
package inc

import java.io.File

import sbt.internal.inc.classfile.JavaCompilerForUnitTesting
import sbt.internal.util.UnitSpec
import xsbti.AnalysisCallback
import xsbti.api.{ ClassLike, DefinitionType }

class ClassToAPISpecification extends UnitSpec {

  "ClassToAPI" should "extract api of inner classes" in {
    val src =
      """|class A {
        |  class B {}
        |}
      """.stripMargin
    val apis = extractApisFromSrc("A.java" -> src).map(c => c.name -> c).toMap
    assert(apis.keySet === Set("A", "A.B"))

    val companionsA = apis("A")
    assert(companionsA.classApi.topLevel === true)
    assert(companionsA.objectApi.topLevel === true)

    val innerClassApiB = findDeclaredInnerClass(companionsA.classApi, "A.B", DefinitionType.ClassDef).get
    assert(innerClassApiB.structure.declared === Array.empty)
    assert(innerClassApiB.structure.inherited === Array.empty)

    val companionsB = apis("A.B")
    assert(companionsB.classApi.topLevel === false)
    assert(companionsB.objectApi.topLevel === false)
    assert(companionsB.classApi.structure.declared.isEmpty === false)
  }

  it should "extract a private inner class" in {
    val src =
      """|class A {
        |  private class B {}
        |}
      """.stripMargin
    val apis = extractApisFromSrc("A.java" -> src).map(c => c.name -> c).toMap
    assert(apis.keySet === Set("A", "A.B"))
  }

  /**
   * Compiles given source code using Java compiler and returns API representation
   * extracted by ClassToAPI class.
   */
  private def extractApisFromSrc(src: (String, String)): Set[Companions] = {
    val (Seq(tempSrcFile), analysisCallback) = JavaCompilerForUnitTesting.compileJavaSrcs(src)(readAPI)
    val apis = analysisCallback.apis(tempSrcFile)
    apis.groupBy(_.name).map((companions _).tupled).toSet
  }

  private def companions(className: String, classes: Set[ClassLike]): Companions = {
    assert(classes.size <= 2, s"Too many classes named $className: $classes")
    def isClass(c: ClassLike) =
      (c.definitionType == DefinitionType.Trait) || (c.definitionType == DefinitionType.ClassDef)
    def isModule(c: ClassLike) =
      (c.definitionType == DefinitionType.Module) || (c.definitionType == DefinitionType.PackageModule)
    // the ClassToAPI always create both class and object APIs
    val classApi = classes.find(isClass).get
    val objectApi = classes.find(isModule).get
    Companions(className, classApi, objectApi)
  }

  private case class Companions(name: String, classApi: ClassLike, objectApi: ClassLike)

  private def findDeclaredInnerClass(classApi: ClassLike, innerClassName: String,
    defType: DefinitionType): Option[ClassLike] = {
    classApi.structure.declared.collectFirst({
      case c: ClassLike if c.name == innerClassName && c.definitionType == defType => c
    })
  }

  def readAPI(callback: AnalysisCallback, source: File, classes: Seq[Class[_]]): Set[(String, String)] = {
    val (apis, inherits) = ClassToAPI.process(classes)
    apis.foreach(callback.api(source, _))
    inherits.map {
      case (from: Class[_], to: Class[_]) => (from.getName, to.getName)
    }
  }

}
