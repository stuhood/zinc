package sbt
package internal
package inc

import java.io.File
import java.lang.ref.{ SoftReference, Reference }

import inc.javac.AnalyzingJavaCompiler
import Locate.DefinesClass
import xsbti.{ AnalysisCallback => XAnalysisCallback, Reporter }
import xsbti.compile.CompileOrder._
import xsbti.compile._
import sbt.io.IO
import sbt.util.{ Logger, InterfaceUtil }

/** An instance of an analyzing compiler that can run both javac + scalac. */
final class MixedAnalyzingCompiler(
  val scalac: AnalyzingCompiler,
  val javac: AnalyzingJavaCompiler,
  val config: CompileConfiguration,
  val log: Logger
) {
  import config._
  import currentSetup._

  private[this] val absClasspath = classpath.map(_.getAbsoluteFile)
  /** Mechanism to work with compiler arguments. */
  private[this] val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)

  /**
   * Compiles the given Java/Scala files.
   *
   * @param include  The files to compile right now
   * @param changes  A list of dependency changes.
   * @param callback  The callback where we report dependency issues.
   */
  def compile(include: Set[File], changes: DependencyChanges, callback: XAnalysisCallback): Unit = {
    val outputDirs = outputDirectories(output)
    outputDirs foreach (IO.createDirectory)
    val incSrc = sources.filter(include)
    val (javaSrcs, scalaSrcs) = incSrc partition javaOnly
    logInputs(log, javaSrcs.size, scalaSrcs.size, outputDirs)
    // compiles the scala code necessary using the analyzing compiler.
    def compileScala(): Unit =
      if (scalaSrcs.nonEmpty) {
        val sources = if (order == Mixed) incSrc else scalaSrcs
        val arguments = cArgs(Nil, absClasspath, None, options.scalacOptions)
        timed("Scala compilation", log) {
          compiler.compile(sources, changes, arguments, output, callback, reporter, config.cache, log, progress)
        }
      }
    // Compiles the Java code necessary.  All analysis code is included in this method.
    def compileJava(): Unit =
      if (javaSrcs.nonEmpty) {
        // Runs the analysis portion of Javac.
        timed("Java compile + analysis", log) {
          javac.compile(javaSrcs, options.javacOptions.toArray[String], output, callback, reporter, log, progress)
        }
      }
    // TODO - Maybe on "Mixed" we should try to compile both Scala + Java.
    if (order == JavaThenScala) { compileJava(); compileScala() } else { compileScala(); compileJava() }
  }

  private[this] def outputDirectories(output: Output): Seq[File] = output match {
    case single: SingleOutput => List(single.outputDirectory)
    case mult: MultipleOutput => mult.outputGroups map (_.outputDirectory)
  }
  // Debugging method to time how long it takes to run various compilation tasks.
  private[this] def timed[T](label: String, log: Logger)(t: => T): T = {
    val start = System.nanoTime
    val result = t
    val elapsed = System.nanoTime - start
    log.debug(label + " took " + (elapsed / 1e9) + " s")
    result
  }

  private[this] def logInputs(log: Logger, javaCount: Int, scalaCount: Int, outputDirs: Seq[File]): Unit = {
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaCount)
    val javaMsg = Analysis.counted("Java source", "", "s", javaCount)
    val combined = scalaMsg ++ javaMsg
    if (combined.nonEmpty)
      log.info(combined.mkString("Compiling ", " and ", " to " + outputDirs.map(_.getAbsolutePath).mkString(",") + "..."))
  }

  /** Returns true if the file is java. */
  private[this] def javaOnly(f: File) = f.getName.endsWith(".java")
}

/**
 * This is a compiler that mixes the `sbt.compiler.AnalyzingCompiler` for Scala incremental compilation
 * with a `xsbti.JavaCompiler`, allowing cross-compilation of mixed Java/Scala projects with analysis output.
 *
 *
 * NOTE: this class *defines* how to run one step of cross-Java-Scala compilation and then delegates
 *       down to the incremental compiler for the rest.
 */
object MixedAnalyzingCompiler {

  def makeConfig(
    scalac: AnalyzingCompiler,
    javac: xsbti.compile.JavaCompiler,
    sources: Seq[File],
    classpath: Seq[File],
    output: Output,
    cache: GlobalsCache,
    progress: Option[CompileProgress] = None,
    options: Seq[String] = Nil,
    javacOptions: Seq[String] = Nil,
    previousAnalysis: CompileAnalysis,
    previousSetup: Option[MiniSetup],
    analysisMap: File => Option[CompileAnalysis] = { _ => None },
    definesClass: DefinesClass = Locate.definesClass _,
    reporter: Reporter,
    compileOrder: CompileOrder = Mixed,
    skip: Boolean = false,
    incrementalCompilerOptions: IncOptions,
    extra: List[(String, String)]
  ): CompileConfiguration =
    {
      val compileSetup = new MiniSetup(output, new MiniOptions(options.toArray, javacOptions.toArray),
        scalac.scalaInstance.actualVersion, compileOrder, incrementalCompilerOptions.nameHashing,
        (extra map InterfaceUtil.t2).toArray)
      config(
        sources,
        classpath,
        compileSetup,
        progress,
        previousAnalysis,
        previousSetup,
        analysisMap,
        definesClass,
        scalac,
        javac,
        reporter,
        skip,
        cache,
        incrementalCompilerOptions
      )
    }

  def config(
    sources: Seq[File],
    classpath: Seq[File],
    setup: MiniSetup,
    progress: Option[CompileProgress],
    previousAnalysis: CompileAnalysis,
    previousSetup: Option[MiniSetup],
    analysis: File => Option[CompileAnalysis],
    definesClass: DefinesClass,
    compiler: AnalyzingCompiler,
    javac: xsbti.compile.JavaCompiler,
    reporter: Reporter,
    skip: Boolean,
    cache: GlobalsCache,
    incrementalCompilerOptions: IncOptions
  ): CompileConfiguration = {
    import MiniSetupUtil._
    new CompileConfiguration(sources, classpath, previousAnalysis, previousSetup, setup,
      progress, analysis, definesClass, reporter, compiler, javac, cache, incrementalCompilerOptions)
  }

  /** Returns the search classpath (for dependencies) and a function which can also do so. */
  def searchClasspathAndLookup(config: CompileConfiguration): (Seq[File], String => Option[File]) = {
    import config._
    import currentSetup._
    val absClasspath = classpath.map(_.getAbsoluteFile)
    val cArgs = new CompilerArguments(compiler.scalaInstance, compiler.cp)
    val searchClasspath = explicitBootClasspath(options.scalacOptions) ++ withBootclasspath(cArgs, absClasspath)
    (searchClasspath, Locate.entry(searchClasspath, definesClass))
  }

  /** Returns a "lookup file for a given class name" function. */
  def classPathLookup(config: CompileConfiguration): String => Option[File] =
    searchClasspathAndLookup(config)._2

  def apply(config: CompileConfiguration)(implicit log: Logger): MixedAnalyzingCompiler = {
    import config._
    val (searchClasspath, entry) = searchClasspathAndLookup(config)
    // Construct a compiler which can handle both java and scala sources.
    new MixedAnalyzingCompiler(
      compiler,
      // TODO - Construction of analyzing Java compiler MAYBE should be earlier...
      new AnalyzingJavaCompiler(javac, classpath, compiler.scalaInstance, entry, searchClasspath),
      config,
      log
    )
  }

  def withBootclasspath(args: CompilerArguments, classpath: Seq[File]): Seq[File] =
    args.bootClasspathFor(classpath) ++ args.extClasspath ++ args.finishClasspath(classpath)
  private[this] def explicitBootClasspath(options: Seq[String]): Seq[File] =
    options.dropWhile(_ != CompilerArguments.BootClasspathOption).slice(1, 2).headOption.toList.flatMap(IO.parseClasspath)

  private[this] val cache = new collection.mutable.HashMap[File, Reference[AnalysisStore]]
  private def staticCache(file: File, backing: => AnalysisStore): AnalysisStore =
    synchronized {
      cache get file flatMap { ref => Option(ref.get) } getOrElse {
        val b = backing
        cache.put(file, new SoftReference(b))
        b
      }
    }

  /** Create a an analysis store cache at the desired location. */
  def staticCachedStore(cacheFile: File) = staticCache(cacheFile, AnalysisStore.sync(AnalysisStore.cached(FileBasedStore(cacheFile))))

}
