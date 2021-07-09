package scala.build.options

final case class JmhOptions(
  addJmhDependencies: Option[String] = None,
  runJmh: Option[Boolean] = None
) {
  def orElse(other: JmhOptions): JmhOptions =
    JmhOptions(
      addJmhDependencies = addJmhDependencies.orElse(other.addJmhDependencies),
      runJmh = runJmh.orElse(other.runJmh)
    )

  def addHashData(update: String => Unit): Unit = {
    for (dep <- addJmhDependencies)
      update("addJmhDependencies=" + dep + "\n")
  }
}