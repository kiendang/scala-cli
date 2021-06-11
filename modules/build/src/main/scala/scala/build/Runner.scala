package scala.build

import coursier.jvm.Execve
import sbt.testing.Status

import java.io.File
import java.nio.file.{Files, Path, Paths}

import scala.cli.testrunner.{AsmTestRunner, TestRunner}
import scala.scalanative.{build => sn}
import scala.util.Properties
import sbt.testing.Framework

object Runner {

  def run(
    javaHome: File,
    javaArgs: Seq[String],
    classPath: Seq[File],
    mainClass: String,
    args: Seq[String],
    logger: Logger,
    allowExecve: Boolean = false
  ): Int = {

    import logger.{log, debug}

    val javaPath = new File(javaHome, "bin/java")
    val command =
      Seq(javaPath.toString) ++
      javaArgs ++
      Seq(
        "-cp", classPath.iterator.map(_.getAbsolutePath).mkString(File.pathSeparator),
        mainClass
      ) ++
      args

    log(
      s"Running ${command.mkString(" ")}",
      "  Running" + System.lineSeparator() + command.iterator.map(_ + System.lineSeparator()).mkString
    )

    if (allowExecve && Execve.available()) {
      debug("execve available")
      Execve.execve(command.head, "java" +: command.tail.toArray, sys.env.toArray.sorted.map { case (k, v) => s"$k=$v" })
      sys.error("should not happen")
    } else
      new ProcessBuilder(command: _*)
        .inheritIO()
        .start()
        .waitFor()
  }

  private def findInPath(app: String): Option[Path] =
    if (Properties.isWin)
      None
    else
      Option(System.getenv("PATH"))
        .iterator
        .flatMap(_.split(File.pathSeparator).iterator)
        .map(Paths.get(_).resolve(app))
        .filter(Files.isExecutable(_))
        .toStream
        .headOption

  def runJs(
    entrypoint: File,
    args: Seq[String],
    logger: Logger,
    allowExecve: Boolean = false
  ): Int = {

    import logger.{log, debug}

    val nodePath = findInPath("node").fold("node")(_.toString)
    val command = Seq(nodePath, entrypoint.getAbsolutePath) ++ args

    log(
      s"Running ${command.mkString(" ")}",
      "  Running" + System.lineSeparator() + command.iterator.map(_ + System.lineSeparator()).mkString
    )

    if (allowExecve && Execve.available()) {
      debug("execve available")
      Execve.execve(command.head, "node" +: command.tail.toArray, sys.env.toArray.sorted.map { case (k, v) => s"$k=$v" })
      sys.error("should not happen")
    } else
      new ProcessBuilder(command: _*)
        .inheritIO()
        .start()
        .waitFor()
  }

  def runNative(
    launcher: File,
    args: Seq[String],
    logger: Logger,
    allowExecve: Boolean = false
  ): Int = {

    import logger.{log, debug}

    val command = Seq(launcher.getAbsolutePath) ++ args

    log(
      s"Running ${command.mkString(" ")}",
      "  Running" + System.lineSeparator() + command.iterator.map(_ + System.lineSeparator()).mkString
    )

    if (allowExecve && Execve.available()) {
      debug("execve available")
      Execve.execve(command.head, launcher.getName +: command.tail.toArray, sys.env.toArray.sorted.map { case (k, v) => s"$k=$v" })
      sys.error("should not happen")
    } else
      new ProcessBuilder(command: _*)
        .inheritIO()
        .start()
        .waitFor()
  }

  private def runTests(
    classPath: Seq[Path],
    framework: Framework,
    parentInspector: AsmTestRunner.ParentInspector
  ): Boolean = {

    val taskDefs =
      AsmTestRunner.taskDefs(
        classPath,
        keepJars = false,
        framework.fingerprints(),
        parentInspector
      ).toArray

    val runner = framework.runner(Array(), Array(), null)
    val initialTasks = runner.tasks(taskDefs)
    val events = TestRunner.runTasks(initialTasks, System.out)

    val doneMsg = runner.done()
    if (doneMsg.nonEmpty)
      System.out.println(doneMsg)

    !events.exists(ev => ev.status == Status.Error || ev.status == Status.Failure || ev.status == Status.Canceled)
  }


  private def frameworkName(classPath: Seq[Path], parentInspector: AsmTestRunner.ParentInspector): String =
    AsmTestRunner.findFrameworkService(classPath)
      .orElse(AsmTestRunner.findFramework(classPath, TestRunner.commonTestFrameworks, parentInspector))
      .getOrElse(sys.error("No test framework found"))
      .replace('/', '.')

  def testJs(
    classPath: Seq[Path],
    entrypoint: File
  ): Int = {
    import org.scalajs.jsenv.Input
    import org.scalajs.jsenv.nodejs.NodeJSEnv
    import org.scalajs.logging.ScalaConsoleLogger
    import org.scalajs.testing.adapter.TestAdapter
    val nodePath = findInPath("node").fold("node")(_.toString)
    val jsEnv = new NodeJSEnv(
      NodeJSEnv.Config()
        .withExecutable(nodePath)
        .withArgs(Nil)
        .withEnv(Map.empty)
        .withSourceMap(NodeJSEnv.SourceMap.Disable)
    )
    val adapterConfig = TestAdapter.Config().withLogger(new ScalaConsoleLogger)
    val inputs = Seq(Input.Script(entrypoint.toPath))
    var adapter: TestAdapter = null

    val parentInspector = new AsmTestRunner.ParentInspector(classPath)
    val frameworkName0 = frameworkName(classPath, parentInspector)

    try {
      adapter = new TestAdapter(jsEnv, inputs, adapterConfig)

      val frameworks = adapter.loadFrameworks(List(List(frameworkName0))).flatten

      if (frameworks.isEmpty)
        sys.error("No framework found by Scala.JS test bridge")
      else if (frameworks.length > 1)
        sys.error("Too many frameworks found by Scala.JS test bridge")
      else {
        val framework = frameworks.head
        val success = runTests(classPath, framework, parentInspector)
        if (success) 0 else 1
      }
    } finally {
      if (adapter != null)
        adapter.close()
    }
  }

  def testNative(
    classPath: Seq[Path],
    launcher: File,
    logger: Logger,
    nativeLogger: sn.Logger
  ): Int = {

    import scala.scalanative.testinterface.adapter.TestAdapter

    val parentInspector = new AsmTestRunner.ParentInspector(classPath)
    val frameworkName0 = frameworkName(classPath, parentInspector)

    val config = TestAdapter.Config()
      .withBinaryFile(launcher)
      .withEnvVars(sys.env.toMap)
      .withLogger(nativeLogger)

    var adapter: TestAdapter = null
    try {
      adapter = new TestAdapter(config)

      val frameworks = adapter.loadFrameworks(List(List(frameworkName0))).flatten

      if (frameworks.isEmpty)
        sys.error("No framework found by Scala-Native test bridge")
      else if (frameworks.length > 1)
        sys.error("Too many frameworks found by Scala-Native test bridge")
      else {
        val framework = frameworks.head
        val success = runTests(classPath, framework, parentInspector)
        if (success) 0 else 1
      }
    } finally {
      if (adapter != null)
        adapter.close()
    }
  }
}