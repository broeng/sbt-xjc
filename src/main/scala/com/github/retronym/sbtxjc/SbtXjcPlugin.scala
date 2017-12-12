package com.github.retronym.sbtxjc

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import sbt.Keys._
import sbt._

import scala.io.Codec
import scala.io.Source

/**
 * Compile Xml Schemata with JAXB XJC.
 */
object SbtXjcPlugin extends AutoPlugin {

  override def requires = sbt.plugins.JvmPlugin

  override def trigger = allRequirements

  object autoImport {
    val xjc            = TaskKey[Seq[File]]("xjc", "Generate JAXB Java sources from XSD files(s)")
    val xjcLibs        = SettingKey[Seq[ModuleID]]("xjc-libs", "Core XJC libraries")
    val xjcPlugins     = SettingKey[Seq[ModuleID]]("xjc-plugins", "Plugins for XJC code generation")
    val xjcJvmOpts     = SettingKey[Seq[String]]("xjc-jvm-opts", "Extra command line parameters to the JVM XJC runs in.")
    val xjcCommandLine = SettingKey[Seq[String]]("xjc-plugin-command-line", "Extra command line parameters to XJC. Can be used to enable a plugin.")
    val xjcBindings    = SettingKey[Seq[String]]("xjc-plugin-bindings", "Binding files to add to XJC.")
  }
  import autoImport._

  /** An Ivy scope for the XJC compiler */
  val XjcTool   = config("xjc-tool").hide

  /** An Ivy scope for XJC compiler plugins, such as the Fluent API plugin */
  val XjcPlugin = config("xjc-plugin").hide

  /** Main settings to enable XSD compilation */
    override lazy val projectSettings = Seq[Def.Setting[_]](
    ivyConfigurations ++= Seq(XjcTool, XjcPlugin),
    xjcCommandLine    := Seq(),
    xjcJvmOpts        := Seq(),
    xjcBindings       := Seq(),
    xjcPlugins        := Seq(),
    xjcLibs           := Seq(
      "org.glassfish.jaxb" % "jaxb-xjc"  % "2.2.11",
      "com.sun.xml.bind"   % "jaxb-impl" % "2.2.11"
    ),
    libraryDependencies ++= xjcLibs.value.map(_ % XjcTool.name),
    libraryDependencies ++= xjcPlugins.value.map(_ % XjcPlugin.name)
  ) ++ xjcSettingsIn(Compile) ++ xjcSettingsIn(Test)

  /** Settings to enable the Fluent API plugin, that provides `withXxx` methods, in addition to `getXxx` and `setXxx`
   *  Requires this resolver http://download.java.net/maven/2/
   **/
  val fluentApiSettings = Seq[Def.Setting[_]](
    xjcPlugins     += "net.java.dev.jaxb2-commons" % "jaxb-fluent-api" % "2.1.8",
    xjcCommandLine += "-Xfluent-api"
  )

  def xjcSettingsIn(conf: Configuration): Seq[Def.Setting[_]] =
    inConfig(conf)(xjcSettings0) ++ Seq(clean := clean.dependsOn(clean in xjc in conf).value)

  /**
   * Unscoped settings, do not use directly, instead use `xjcSettingsIn(IntegrationTest)`
   */
  private def xjcSettings0 = Seq[Def.Setting[_]](
    sources in xjc       := unmanagedResourceDirectories.value.flatMap(dirs => (dirs ** "*.xsd").get),
    xjc                  := xjcCompile(javaHome.value, (classpathTypes in xjc).value, update.value, (sources in xjc).value,
                              (sourceManaged in xjc).value, xjcCommandLine.value, xjcJvmOpts.value, xjcBindings.value, streams.value),
    sourceGenerators     += xjc,
    clean in xjc         := xjcClean((sourceManaged in xjc).value, streams.value)
  )

  /**
   * @return the .java files in `sourceManaged` after compilation.
   */
  private def xjcCompile(javaHome: Option[File], classpathTypes: Set[String], updateReport: UpdateReport,
                         xjcSources: Seq[File], sourceManaged: File, extraCommandLine: Seq[String], xjcJvmOpts: Seq[String],
                         xjcBindings: Seq[String], streams: TaskStreams): Seq[File] = {
    import streams.log
    def generated = (sourceManaged ** "*.java").get

    val compileOutputTmp =
      new File(sourceManaged.getParentFile, "xjc_tmp")

    val shouldProcess = (xjcSources, generated) match {
      case (Seq(), _)  => false
      case (_, Seq())  => true
      case (ins, outs) =>
        val inLastMod = ins.map(_.lastModified()).max
        val outLasMod = outs.map(_.lastModified()).min
        outLasMod < inLastMod
    }

    lazy val options: Seq[String] = {
      import File.pathSeparator
      def jars(config: Configuration): Seq[File] = Classpaths.managedJars(config, classpathTypes, updateReport).map(_.data)
      val pluginJars      = jars(XjcPlugin)
      val mainJars        = jars(XjcTool)
      val jvmCpOptions    = Seq("-classpath", mainJars.mkString(pathSeparator))
      val xsdSourcePaths  = xjcSources.map(_.getAbsolutePath)
      val pluginCpOptions = pluginJars match {
        case Seq() => Seq()
        case js    => Seq("-extension", "-classpath", js.mkString(pathSeparator))
      }
      val appOptions = pluginCpOptions ++ Seq("-d", compileOutputTmp.getAbsolutePath)
      val mainClass  = "com.sun.tools.xjc.XJCFacade"
      val bindings = xjcBindings.map(List("-b",_)).flatten
      jvmCpOptions ++ xjcJvmOpts ++ List(mainClass) ++ appOptions ++ extraCommandLine ++ xsdSourcePaths ++ bindings
    }

    if (shouldProcess) {
      compileOutputTmp.mkdirs()
      sourceManaged.mkdirs()
      log.info("Compiling %d XSD file(s) to %s".format(xjcSources.size, sourceManaged.getAbsolutePath))
      log.debug("XJC java command line: " + options.mkString("\n"))
      val returnCode = Forker(javaHome, options, log)
      if (returnCode != 0) sys.error("Non zero return code from xjc [%d]".format(returnCode))
      // Copy any changed files
      (compileOutputTmp ** "*.java").get.foreach { sourceFile =>
        copyIfChanged(sourceManaged, sourceFile, compileOutputTmp, log.info(_))
      }
    } else {
      log.debug("No sources newer than products, skipping.")
    }

    generated
  }

  private def copyIfChanged(sourceManaged: File, sourceFile: File, sourceFileRoot: File, log: String => Unit): Unit = {
    val relativeFilePath = sourceFile.getAbsolutePath.substring(sourceFileRoot.getAbsolutePath.length)
    val destinationFile = new File(sourceManaged, relativeFilePath)
    // Ensure destination folders exist
    destinationFile.getParentFile.mkdirs()
    // Copy the file if it exists
    readFile(sourceFile) foreach { generatedContent: String =>
      // ... and has updated content
      if (!readFile(destinationFile).exists(_ == generatedContent)) {
        log(s"Copying ${sourceFile.getAbsolutePath} -> ${destinationFile.getAbsolutePath}")
        Files.write(
          destinationFile.toPath,
          Files.readAllBytes(sourceFile.toPath),
          StandardOpenOption.WRITE,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING)
      }
      // Remove the source file
      sourceFile.delete()
    }
  }

  private def readFile(file: File): Option[String] =
    if (!file.exists()) {
      None
    } else {
      Some(Source.fromFile(file, Codec.UTF8.name).mkString)
    }

  private def xjcClean(sourceManaged: File, streams: TaskStreams) {
    import streams.log
    val filesToDelete = (sourceManaged ** "*.java").get
    log.debug("Cleaning Files:\n%s".format(filesToDelete.mkString("\n")))
    if (filesToDelete.nonEmpty) {
      log.info("Cleaning %d XJC generated files in %s".format(filesToDelete.size, sourceManaged.getAbsolutePath))
      IO.delete(filesToDelete)
    }
  }
}
