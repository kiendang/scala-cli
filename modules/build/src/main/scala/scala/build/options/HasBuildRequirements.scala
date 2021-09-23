package scala.build.options

final case class HasBuildRequirements[+T](
  requirements: BuildRequirements,
  value: T
) {
  def withScalaVersion(sv: String): Either[String, HasBuildRequirements[T]] =
    requirements.withScalaVersion(sv).map { updatedRequirements =>
      copy(requirements = updatedRequirements)
    }
  def withPlatform(pf: BuildRequirements.Platform): Either[String, HasBuildRequirements[T]] =
    requirements.withPlatform(pf).map { updatedRequirements =>
      copy(requirements = updatedRequirements)
    }
}