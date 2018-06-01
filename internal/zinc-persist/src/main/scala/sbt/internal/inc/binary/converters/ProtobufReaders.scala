/*
 * Zinc - The incremental compiler for Scala.
 * Copyright 2011 - 2017, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * This software is released under the terms written in LICENSE.
 */

package sbt.internal.inc.binary.converters

import java.io.File
import scala.collection.mutable

import sbt.internal.inc.Relations.ClassDependencies
import sbt.internal.inc._
import sbt.util.InterfaceUtil
import xsbti.{ Position, Problem, Severity, T2, UseScope }
import xsbti.compile.{ CompileOrder, FileHash, MiniOptions, MiniSetup, Output, OutputGroup }
import xsbti.compile.analysis.{ Compilation, ReadMapper, SourceInfo, Stamp }
import sbt.internal.inc.binary.converters.ProtobufDefaults.Feedback.StringToException
import sbt.internal.inc.binary.converters.ProtobufDefaults.Feedback.{ Readers => ReadersFeedback }
import sbt.internal.inc.binary.converters.ProtobufDefaults.{ Classes, ReadersConstants }
import sbt.internal.util.Relation
import xsbti.api._

/**
 * Each instance of this class will intern (most) deserialized strings in one (instance-level)
 * pool, so it's recommended to use a new instance per deserialized top-level object (generally
 * `AnalysisFile`).
 */
final class ProtobufReaders(mapper: ReadMapper) {
  private[this] val internMap = mutable.Map[String, String]()

  private[this] def intern(str: String): String = internMap.getOrElseUpdate(str, str)

  def fromPathString(path: String): File = {
    java.nio.file.Paths.get(intern(path)).toFile
  }

  def fromStampType(stampType: schema.Stamps.StampType): Stamp = {
    import sbt.internal.inc.{ EmptyStamp, LastModified, Hash }
    stampType.`type` match {
      case schema.Stamps.StampType.Type.Empty            => EmptyStamp
      case schema.Stamps.StampType.Type.Hash(h)          => Hash.unsafeFromString(h.hash) // fair assumption
      case schema.Stamps.StampType.Type.LastModified(lm) => new LastModified(lm.millis)
    }
  }

  def fromStamps(stamps: schema.Stamps): Stamps = {
    // Note that boilerplate here is inteded, abstraction is expensive
    def fromBinarySchemaMap(stamps: Map[String, schema.Stamps.StampType]): Map[File, Stamp] = {
      stamps.map {
        case (path, schemaStamp) =>
          val file = fromPathString(path)
          val newFile = mapper.mapBinaryFile(file)
          val stamp = fromStampType(schemaStamp)
          val newStamp = mapper.mapBinaryStamp(newFile, stamp)
          newFile -> newStamp
      }
    }

    def fromSourceSchemaMap(stamps: Map[String, schema.Stamps.StampType]): Map[File, Stamp] = {
      stamps.map {
        case (path, schemaStamp) =>
          val file = fromPathString(path)
          val newFile = mapper.mapSourceFile(file)
          val stamp = fromStampType(schemaStamp)
          val newStamp = mapper.mapSourceStamp(newFile, stamp)
          newFile -> newStamp
      }
    }

    def fromProductSchemaMap(stamps: Map[String, schema.Stamps.StampType]): Map[File, Stamp] = {
      stamps.map {
        case (path, schemaStamp) =>
          val file = fromPathString(path)
          val newFile = mapper.mapProductFile(file)
          val stamp = fromStampType(schemaStamp)
          val newStamp = mapper.mapProductStamp(newFile, stamp)
          newFile -> newStamp
      }
    }

    val binaries = fromBinarySchemaMap(stamps.binaryStamps)
    val sources = fromSourceSchemaMap(stamps.sourceStamps)
    val products = fromProductSchemaMap(stamps.productStamps)
    Stamps(
      binaries = binaries,
      sources = sources,
      products = products
    )
  }

  def fromOutputGroup(outputGroup: schema.OutputGroup): OutputGroup = {
    val sourcePath = fromPathString(outputGroup.sourcePath)
    val sourceDir = mapper.mapSourceDir(sourcePath)
    val targetPath = fromPathString(outputGroup.targetPath)
    val targetDir = mapper.mapOutputDir(targetPath)
    SimpleOutputGroup(sourceDir, targetDir)
  }

  def fromCompilationOutput(output: schema.Compilation.Output): Output = {
    import schema.Compilation.{ Output => CompilationOutput }
    output match {
      case CompilationOutput.SingleOutput(single) =>
        val target = fromPathString(single.target)
        val outputDir = mapper.mapOutputDir(target)
        new ConcreteSingleOutput(outputDir)
      case CompilationOutput.MultipleOutput(multiple) =>
        val groups = multiple.outputGroups.iterator.map(fromOutputGroup).toArray
        new ConcreteMultipleOutput(groups)
      case CompilationOutput.Empty =>
        ReadersFeedback.ExpectedOutputInCompilationOutput.!!
    }
  }

  def fromCompilation(compilation: schema.Compilation): Compilation = {
    val output = fromCompilationOutput(compilation.output)
    new sbt.internal.inc.Compilation(compilation.startTimeMillis, output)
  }

  def fromCompilations(compilations0: schema.Compilations): Compilations = {
    val compilations = compilations0.compilations.map(fromCompilation).toList
    val castedCompilations = compilations.map { case c: sbt.internal.inc.Compilation => c }
    Compilations.of(castedCompilations)
  }

  def fromPosition(position: schema.Position): Position = {
    import ProtobufDefaults.{ MissingString, MissingInt }
    def fromString(value: String): Option[String] =
      if (value == MissingString) None else Some(intern(value))
    def fromInt(value: Int): Option[Integer] =
      if (value == MissingInt) None else Some(value)
    InterfaceUtil.position(
      line0 = fromInt(position.line),
      content = position.lineContent,
      offset0 = fromInt(position.offset),
      pointer0 = fromInt(position.pointer),
      pointerSpace0 = fromString(position.pointerSpace),
      sourcePath0 = fromString(position.sourcePath),
      sourceFile0 = fromString(position.sourceFilepath).map(fromPathString)
    )
  }

  def fromSeverity(severity: schema.Severity): Severity = {
    severity match {
      case schema.Severity.INFO             => Severity.Info
      case schema.Severity.WARN             => Severity.Warn
      case schema.Severity.ERROR            => Severity.Error
      case schema.Severity.Unrecognized(id) => ReadersFeedback.unrecognizedSeverity(id).!!
    }
  }

  def fromProblem(problem: schema.Problem): Problem = {
    val category = problem.category
    val message = problem.message
    val severity = fromSeverity(problem.severity)
    val position = problem.position
      .map(fromPosition)
      .getOrElse(ReadersFeedback.ExpectedPositionInProblem.!!)
    InterfaceUtil.problem(category, position, message, severity)
  }

  def fromSourceInfo(sourceInfo: schema.SourceInfo): SourceInfo = {
    val mainClasses = sourceInfo.mainClasses.map(intern)
    val reportedProblems = sourceInfo.reportedProblems.map(fromProblem)
    val unreportedProblems = sourceInfo.unreportedProblems.map(fromProblem)
    SourceInfos.makeInfo(reported = reportedProblems,
                         unreported = unreportedProblems,
                         mainClasses = mainClasses)
  }

  def fromSourceInfos(sourceInfos0: schema.SourceInfos): SourceInfos = {
    val sourceInfos = sourceInfos0.sourceInfos.iterator.map {
      case (path, value) =>
        val file = mapper.mapSourceFile(fromPathString(path))
        val sourceInfo = fromSourceInfo(value)
        file -> sourceInfo
    }
    SourceInfos.of(sourceInfos.toMap)
  }

  def fromClasspathFileHash(fileHash: schema.FileHash): FileHash = {
    val hash = fileHash.hash
    val classpathEntry = fromPathString(fileHash.path)
    val newClasspathEntry = mapper.mapClasspathEntry(classpathEntry)
    FileHash.of(newClasspathEntry, hash)
  }

  def fromMiniOptions(miniOptions: schema.MiniOptions): MiniOptions = {
    val classpathHash = miniOptions.classpathHash.map(fromClasspathFileHash).toArray
    val javacOptions = miniOptions.javacOptions.map(mapper.mapJavacOption).toArray
    val scalacOptions = miniOptions.scalacOptions.map(mapper.mapScalacOption).toArray
    MiniOptions.of(classpathHash, scalacOptions, javacOptions)
  }

  def fromCompileOrder(compileOrder: schema.CompileOrder): CompileOrder = {
    compileOrder match {
      case schema.CompileOrder.MIXED            => CompileOrder.Mixed
      case schema.CompileOrder.JAVATHENSCALA    => CompileOrder.JavaThenScala
      case schema.CompileOrder.SCALATHENJAVA    => CompileOrder.ScalaThenJava
      case schema.CompileOrder.Unrecognized(id) => ReadersFeedback.unrecognizedOrder(id).!!
    }
  }

  def fromStringTuple(tuple: schema.Tuple): T2[String, String] = {
    InterfaceUtil.t2(tuple.first -> tuple.second)
  }

  def fromMiniSetupOutput(output: schema.MiniSetup.Output): Output = {
    import schema.MiniSetup.{ Output => MiniSetupOutput }
    output match {
      case MiniSetupOutput.SingleOutput(single) =>
        val targetDir = fromPathString(single.target)
        val outputDir = mapper.mapOutputDir(targetDir)
        new ConcreteSingleOutput(outputDir)
      case MiniSetupOutput.MultipleOutput(multiple) =>
        val groups = multiple.outputGroups.iterator.map(fromOutputGroup).toArray
        new ConcreteMultipleOutput(groups)
      case MiniSetupOutput.Empty =>
        ReadersFeedback.ExpectedOutputInCompilationOutput.!!
    }
  }

  def fromMiniSetup(miniSetup: schema.MiniSetup): MiniSetup = {
    val output = fromMiniSetupOutput(miniSetup.output)
    val miniOptions = miniSetup.miniOptions
      .map(fromMiniOptions)
      .getOrElse(ReadersFeedback.ExpectedMiniOptionsInSetup.!!)
    val compilerVersion = miniSetup.compilerVersion
    val compileOrder = fromCompileOrder(miniSetup.compileOrder)
    val storeApis = miniSetup.storeApis
    val extra = miniSetup.extra.map(fromStringTuple).toArray
    MiniSetup.of(output, miniOptions, compilerVersion, compileOrder, storeApis, extra)
  }

  implicit class EfficientTraverse[T](seq: Seq[T]) {
    def toZincArray[R: scala.reflect.ClassTag](f: T => R): Array[R] =
      seq.iterator.map(f).toArray
  }

  implicit class OptionReader[T](option: Option[T]) {
    def read[R](from: T => R, errorMessage: => String) =
      option.fold(errorMessage.!!)(from)
  }

  def fromPath(path: schema.Path): Path = {
    def fromPathComponent(pathComponent: schema.Path.PathComponent): PathComponent = {
      import ReadersFeedback.ExpectedPathInSuper
      import schema.Path.{ PathComponent => SchemaPath }
      import SchemaPath.{ Component => SchemaComponent }
      import Classes.{ Component, PathComponent }
      pathComponent.component match {
        case SchemaComponent.Id(c) => Id.of(intern(c.id))
        case SchemaComponent.Super(c) =>
          Super.of(c.qualifier.read(fromPath, ExpectedPathInSuper))
        case SchemaComponent.This(_) => ReadersConstants.This
        case SchemaComponent.Empty   => ReadersFeedback.expected(Component, PathComponent).!!
      }
    }
    val components = path.components.toZincArray(fromPathComponent)
    Path.of(components)
  }

  def fromAnnotation(annotation: schema.Annotation): Annotation = {
    def fromAnnotationArgument(argument: schema.AnnotationArgument): AnnotationArgument = {
      val name = intern(argument.name)
      val value = intern(argument.value)
      AnnotationArgument.of(name, value)
    }

    val arguments = annotation.arguments.toZincArray(fromAnnotationArgument)
    val base = annotation.base.read(fromType, ReadersFeedback.expectedBaseIn(Classes.Annotation))
    Annotation.of(base, arguments)
  }

  def fromStructure(tpe: schema.Type.Structure): Structure = {
    def `lazy`[T](value: T): Lazy[T] = SafeLazyProxy.strict(value)
    val parents = `lazy`(tpe.parents.toZincArray(fromType))
    val declared = `lazy`(tpe.declared.toZincArray(fromClassDefinition))
    val inherited = `lazy`(tpe.inherited.toZincArray(fromClassDefinition))
    Structure.of(parents, declared, inherited)
  }

  def fromType(`type`: schema.Type): Type = {
    import ReadersFeedback.expectedBaseIn
    def fromParameterRef(tpe: schema.Type.ParameterRef): ParameterRef = {
      ParameterRef.of(intern(tpe.id))
    }

    def fromParameterized(tpe: schema.Type.Parameterized): Parameterized = {
      val baseType = tpe.baseType.read(fromType, expectedBaseIn(Classes.Parameterized))
      val typeArguments = tpe.typeArguments.toZincArray(fromType)
      Parameterized.of(baseType, typeArguments)
    }

    def fromPolymorphic(tpe: schema.Type.Polymorphic): Polymorphic = {
      val baseType = tpe.baseType.read(fromType, expectedBaseIn(Classes.Polymorphic))
      val typeParameters = tpe.typeParameters.toZincArray(fromTypeParameter)
      Polymorphic.of(baseType, typeParameters)
    }

    def fromConstant(tpe: schema.Type.Constant): Constant = {
      val baseType = tpe.baseType.read(fromType, expectedBaseIn(Classes.Constant))
      val value = intern(tpe.value)
      Constant.of(baseType, value)
    }

    def fromExistential(tpe: schema.Type.Existential): Existential = {
      val baseType = tpe.baseType.read(fromType, expectedBaseIn(Classes.Existential))
      val clause = tpe.clause.toZincArray(fromTypeParameter)
      Existential.of(baseType, clause)
    }

    def fromSingleton(tpe: schema.Type.Singleton): Singleton = {
      val path = tpe.path.read(fromPath, ReadersFeedback.ExpectedPathInSingleton)
      Singleton.of(path)
    }

    def fromProjection(tpe: schema.Type.Projection): Projection = {
      val id = intern(tpe.id)
      val prefix = tpe.prefix.read(fromType, ReadersFeedback.ExpectedPrefixInProjection)
      Projection.of(prefix, id)
    }

    def fromAnnotated(tpe: schema.Type.Annotated): Annotated = {
      val baseType = tpe.baseType.read(fromType, expectedBaseIn(Classes.Annotated))
      val annotations = tpe.annotations.toZincArray(fromAnnotation)
      Annotated.of(baseType, annotations)
    }

    `type`.value match {
      case schema.Type.Value.ParameterRef(tpe)  => fromParameterRef(tpe)
      case schema.Type.Value.Parameterized(tpe) => fromParameterized(tpe)
      case schema.Type.Value.Structure(tpe)     => fromStructure(tpe)
      case schema.Type.Value.Polymorphic(tpe)   => fromPolymorphic(tpe)
      case schema.Type.Value.Constant(tpe)      => fromConstant(tpe)
      case schema.Type.Value.Existential(tpe)   => fromExistential(tpe)
      case schema.Type.Value.Singleton(tpe)     => fromSingleton(tpe)
      case schema.Type.Value.Projection(tpe)    => fromProjection(tpe)
      case schema.Type.Value.Annotated(tpe)     => fromAnnotated(tpe)
      case schema.Type.Value.EmptyType(_)       => ReadersConstants.EmptyType
      case schema.Type.Value.Empty              => ReadersFeedback.ExpectedNonEmptyType.!!
    }
  }

  def fromModifiers(modifiers: schema.Modifiers): Modifiers =
    InternalApiProxy.Modifiers(modifiers.flags)

  def fromAccess(access: schema.Access): Access = {
    def fromQualifier(qualifier: schema.Qualifier): Qualifier = {
      import schema.Qualifier.{ Type => QualifierType }
      qualifier.`type` match {
        case QualifierType.IdQualifier(q)   => IdQualifier.of(intern(q.value))
        case QualifierType.ThisQualifier(_) => ReadersConstants.ThisQualifier
        case QualifierType.Unqualified(_)   => ReadersConstants.Unqualified
        case QualifierType.Empty            => ReadersFeedback.ExpectedNonEmptyQualifier.!!
      }
    }

    def readQualifier(qualifier: Option[schema.Qualifier]): Qualifier =
      qualifier.read(fromQualifier, ReadersFeedback.ExpectedQualifierInAccess)

    access.`type` match {
      case schema.Access.Type.Public(_)    => ReadersConstants.Public
      case schema.Access.Type.Protected(a) => Protected.of(readQualifier(a.qualifier))
      case schema.Access.Type.Private(a)   => Private.of(readQualifier(a.qualifier))
      case schema.Access.Type.Empty        => ReadersFeedback.ExpectedValidAccessType.!!
    }
  }

  def fromDefinitionType(definitionType: schema.DefinitionType): DefinitionType = {
    definitionType match {
      case schema.DefinitionType.CLASSDEF        => DefinitionType.ClassDef
      case schema.DefinitionType.MODULE          => DefinitionType.Module
      case schema.DefinitionType.TRAIT           => DefinitionType.Trait
      case schema.DefinitionType.PACKAGEMODULE   => DefinitionType.PackageModule
      case schema.DefinitionType.Unrecognized(_) => ReadersFeedback.UnrecognizedDefinitionType.!!
    }
  }

  def fromClassDefinition(classDefinition: schema.ClassDefinition): ClassDefinition = {
    import ReadersFeedback.{ MissingModifiersInDef, MissingAccessInDef, expectedTypeIn }
    import ReadersFeedback.{
      ExpectedReturnTypeInDef,
      ExpectedLowerBoundInTypeDeclaration,
      ExpectedUpperBoundInTypeDeclaration
    }

    val name = intern(classDefinition.name)
    val access = classDefinition.access.read(fromAccess, MissingAccessInDef)
    val modifiers = classDefinition.modifiers.read(fromModifiers, MissingModifiersInDef)
    val annotations = classDefinition.annotations.toZincArray(fromAnnotation)

    def fromParameterList(parameterList: schema.ParameterList): ParameterList = {
      def fromMethodParameter(methodParameter: schema.MethodParameter): MethodParameter = {
        def fromParameterModifier(modifier: schema.ParameterModifier): ParameterModifier = {
          modifier match {
            case schema.ParameterModifier.PLAIN    => ParameterModifier.Plain
            case schema.ParameterModifier.BYNAME   => ParameterModifier.ByName
            case schema.ParameterModifier.REPEATED => ParameterModifier.Repeated
            case schema.ParameterModifier.Unrecognized(_) =>
              ReadersFeedback.UnrecognizedParamModifier.!!
          }
        }
        val name = intern(methodParameter.name)
        val hasDefault = methodParameter.hasDefault
        val `type` = methodParameter.`type`.read(fromType, expectedTypeIn(Classes.MethodParameter))
        val modifier = fromParameterModifier(methodParameter.modifier)
        MethodParameter.of(name, `type`, hasDefault, modifier)
      }

      val isImplicit = parameterList.isImplicit
      val parameters = parameterList.parameters.toZincArray(fromMethodParameter)
      ParameterList.of(parameters, isImplicit)
    }

    def fromClassLikeDef(defDef: schema.ClassDefinition.ClassLikeDef): ClassLikeDef = {
      val definitionType = fromDefinitionType(defDef.definitionType)
      val typeParameters = defDef.typeParameters.toZincArray(fromTypeParameter)
      ClassLikeDef.of(name, access, modifiers, annotations, typeParameters, definitionType)
    }

    def fromDefDef(defDef: schema.ClassDefinition.Def): Def = {
      val returnType = defDef.returnType.read(fromType, ExpectedReturnTypeInDef)
      val typeParameters = defDef.typeParameters.toZincArray(fromTypeParameter)
      val valueParameters = defDef.valueParameters.toZincArray(fromParameterList)
      Def.of(name, access, modifiers, annotations, typeParameters, valueParameters, returnType)
    }

    def fromValDef(valDef: schema.ClassDefinition.Val): Val = {
      val `type` = valDef.`type`.read(fromType, expectedTypeIn(Classes.Val))
      Val.of(name, access, modifiers, annotations, `type`)
    }

    def fromVarDef(varDef: schema.ClassDefinition.Var): Var = {
      val `type` = varDef.`type`.read(fromType, expectedTypeIn(Classes.Var))
      Var.of(name, access, modifiers, annotations, `type`)
    }

    def fromTypeAlias(typeAlias: schema.ClassDefinition.TypeAlias): TypeAlias = {
      val `type` = typeAlias.`type`.read(fromType, expectedTypeIn(Classes.TypeAlias))
      val typeParameters = typeAlias.typeParameters.toZincArray(fromTypeParameter)
      TypeAlias.of(name, access, modifiers, annotations, typeParameters, `type`)
    }

    def fromTypeDeclaration(decl: schema.ClassDefinition.TypeDeclaration): TypeDeclaration = {
      val lowerBound = decl.lowerBound.read(fromType, ExpectedLowerBoundInTypeDeclaration)
      val upperBound = decl.upperBound.read(fromType, ExpectedUpperBoundInTypeDeclaration)
      val typeParams = decl.typeParameters.toZincArray(fromTypeParameter)
      TypeDeclaration.of(name, access, modifiers, annotations, typeParams, lowerBound, upperBound)
    }

    import schema.ClassDefinition.{ Extra => DefType }
    classDefinition.extra match {
      case DefType.ClassLikeDef(d)    => fromClassLikeDef(d)
      case DefType.DefDef(d)          => fromDefDef(d)
      case DefType.ValDef(d)          => fromValDef(d)
      case DefType.VarDef(d)          => fromVarDef(d)
      case DefType.TypeAlias(d)       => fromTypeAlias(d)
      case DefType.TypeDeclaration(d) => fromTypeDeclaration(d)
      case DefType.Empty              => ReadersFeedback.ExpectedNonEmptyDefType.!!
    }
  }

  def fromTypeParameter(typeParameter: schema.TypeParameter): TypeParameter = {
    def fromVariance(variance: schema.Variance): Variance = {
      variance match {
        case schema.Variance.INVARIANT       => Variance.Invariant
        case schema.Variance.COVARIANT       => Variance.Covariant
        case schema.Variance.CONTRAVARIANT   => Variance.Contravariant
        case schema.Variance.Unrecognized(_) => ReadersFeedback.UnrecognizedVariance.!!
      }
    }

    import ReadersFeedback.{ ExpectedLowerBoundInTypeParameter, ExpectedUpperBoundInTypeParameter }
    val id = intern(typeParameter.id)
    val annotations = typeParameter.annotations.toZincArray(fromAnnotation)
    val typeParameters = typeParameter.typeParameters.toZincArray(fromTypeParameter)
    val variance = fromVariance(typeParameter.variance)
    val lowerBound = typeParameter.lowerBound.read(fromType, ExpectedLowerBoundInTypeParameter)
    val upperBound = typeParameter.upperBound.read(fromType, ExpectedUpperBoundInTypeParameter)
    TypeParameter.of(id, annotations, typeParameters, variance, lowerBound, upperBound)
  }

  def fromClassLike(classLike: schema.ClassLike): ClassLike = {
    def expectedMsg(msg: String) = ReadersFeedback.expected(msg, Classes.ClassLike)
    def expected(clazz: Class[_]) = expectedMsg(clazz.getName)
    val name = intern(classLike.name)
    val access = classLike.access.read(fromAccess, expected(Classes.Access))
    val modifiers = classLike.modifiers.read(fromModifiers, expected(Classes.Modifiers))
    val annotations = classLike.annotations.toZincArray(fromAnnotation)

    import SafeLazyProxy.{ strict => mkLazy }
    val definitionType = fromDefinitionType(classLike.definitionType)
    val selfType = mkLazy(classLike.selfType.read(fromType, expectedMsg("self type")))
    val structure = mkLazy(classLike.structure.read(fromStructure, expected(Classes.Structure)))
    val savedAnnotations = classLike.savedAnnotations.map(intern).toArray
    val childrenOfSealedClass = classLike.childrenOfSealedClass.toZincArray(fromType)
    val topLevel = classLike.topLevel
    val typeParameters = classLike.typeParameters.toZincArray(fromTypeParameter)
    ClassLike.of(
      name,
      access,
      modifiers,
      annotations,
      definitionType,
      selfType,
      structure,
      savedAnnotations,
      childrenOfSealedClass,
      topLevel,
      typeParameters
    )
  }

  def fromUseScope(useScope: schema.UseScope): UseScope = {
    useScope match {
      case schema.UseScope.DEFAULT          => UseScope.Default
      case schema.UseScope.IMPLICIT         => UseScope.Implicit
      case schema.UseScope.PATMAT           => UseScope.PatMatTarget
      case schema.UseScope.Unrecognized(id) => ReadersFeedback.unrecognizedUseScope(id).!!
    }
  }

  def fromAnalyzedClass(analyzedClass: schema.AnalyzedClass): AnalyzedClass = {
    def fromCompanions(companions: schema.Companions): Companions = {
      def expected(msg: String) = ReadersFeedback.expected(msg, Classes.Companions)
      val classApi = companions.classApi.read(fromClassLike, expected("class api"))
      val objectApi = companions.objectApi.read(fromClassLike, expected("object api"))
      Companions.of(classApi, objectApi)
    }

    def fromNameHash(nameHash: schema.NameHash): NameHash = {
      val name = intern(nameHash.name)
      val hash = nameHash.hash
      val scope = fromUseScope(nameHash.scope)
      NameHash.of(name, scope, hash)
    }

    import SafeLazyProxy.{ strict => mkLazy }
    import ReadersFeedback.ExpectedCompanionsInAnalyzedClass
    val compilationTimestamp = analyzedClass.compilationTimestamp
    val name = intern(analyzedClass.name)
    val api = mkLazy(analyzedClass.api.read(fromCompanions, ExpectedCompanionsInAnalyzedClass))
    val apiHash = analyzedClass.apiHash
    val nameHashes = analyzedClass.nameHashes.toZincArray(fromNameHash)
    val hasMacro = analyzedClass.hasMacro
    AnalyzedClass.of(compilationTimestamp, name, api, apiHash, nameHashes, hasMacro)
  }

  private final val stringId = intern _
  private final val stringToFile = (path: String) => fromPathString(path)
  def fromRelations(relations: schema.Relations): Relations = {
    def fromMap[K, V](map: Map[String, schema.Values],
                      fk: String => K,
                      fv: String => V): Relation[K, V] = {
      val forwardMap = map.iterator.map {
        case (k, vs) =>
          val values = vs.values.iterator.map(fv).toSet
          fk(k) -> values
      }
      Relation.reconstruct(forwardMap.toMap)
    }

    def fromClassDependencies(classDependencies: schema.ClassDependencies): ClassDependencies = {
      val internal = fromMap(classDependencies.internal, stringId, stringId)
      val external = fromMap(classDependencies.external, stringId, stringId)
      new ClassDependencies(internal, external)
    }

    def fromUsedName(usedName: schema.UsedName): UsedName = {
      val name = intern(usedName.name)
      val scopes = usedName.scopes.iterator.map(fromUseScope).toIterable
      UsedName.apply(name, scopes)
    }

    def fromUsedNamesMap(map: Map[String, schema.UsedNames]): Relation[String, UsedName] = {
      val forwardMap = map.mapValues(values => values.usedNames.iterator.map(fromUsedName).toSet)
      Relation.reconstruct(forwardMap)
    }

    def expected(msg: String) = ReadersFeedback.expected(msg, Classes.Relations)

    val srcProd = fromMap(relations.srcProd, stringToFile, stringToFile)
    val libraryDep = fromMap(relations.libraryDep, stringToFile, stringToFile)
    val libraryClassName = fromMap(relations.libraryClassName, stringToFile, stringId)
    val memberRef = relations.memberRef.read(fromClassDependencies, expected("member refs"))
    val inheritance = relations.inheritance.read(fromClassDependencies, expected("inheritance"))
    val localInheritance =
      relations.localInheritance.read(fromClassDependencies, expected("local inheritance"))
    val classes = fromMap(relations.classes, stringToFile, stringId)
    val productClassName = fromMap(relations.productClassName, stringId, stringId)
    val names = fromUsedNamesMap(relations.names)
    val internal = InternalDependencies(
      Map(
        DependencyContext.DependencyByMemberRef -> memberRef.internal,
        DependencyContext.DependencyByInheritance -> inheritance.internal,
        DependencyContext.LocalDependencyByInheritance -> localInheritance.internal
      )
    )
    val external = ExternalDependencies(
      Map(
        DependencyContext.DependencyByMemberRef -> memberRef.external,
        DependencyContext.DependencyByInheritance -> inheritance.external,
        DependencyContext.LocalDependencyByInheritance -> localInheritance.external
      )
    )
    Relations.make(
      srcProd,
      libraryDep,
      libraryClassName,
      internal,
      external,
      classes,
      names,
      productClassName
    )
  }

  def fromApis(apis: schema.APIs): APIs = {
    val internal = apis.internal.map { case (k, v) => intern(k) -> fromAnalyzedClass(v) }
    val external = apis.external.map { case (k, v) => intern(k) -> fromAnalyzedClass(v) }
    APIs(internal = internal, external = external)
  }

  def fromApisFile(apisFile: schema.APIsFile): (APIs, schema.Version) = {
    val apis = apisFile.apis.read(fromApis, ReadersFeedback.ExpectedApisInApisFile)
    val version = apisFile.version
    apis -> version
  }

  def fromAnalysis(analysis: schema.Analysis): Analysis = {
    def expected(clazz: Class[_]) = ReadersFeedback.expected(clazz, Classes.Analysis)
    val stamps = analysis.stamps.read(fromStamps, expected(Classes.Stamps))
    val relations = analysis.relations.read(fromRelations, expected(Classes.Relations))
    val sourceInfos = analysis.sourceInfos.read(fromSourceInfos, expected(Classes.SourceInfos))
    val compilations = analysis.compilations.read(fromCompilations, expected(Classes.Compilations))
    Analysis.Empty.copy(
      stamps = stamps,
      relations = relations,
      infos = sourceInfos,
      compilations = compilations
    )
  }

  def fromAnalysisFile(analysisFile: schema.AnalysisFile): (Analysis, MiniSetup, schema.Version) = {
    val version = analysisFile.version
    val analysis = analysisFile.analysis.read(fromAnalysis, ???)
    val miniSetup = analysisFile.miniSetup.read(fromMiniSetup, ???)
    (analysis, miniSetup, version)
  }
}
