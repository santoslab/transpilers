import mill._
import mill.scalalib._
import ammonite.ops.up
import org.sireum.mill.SireumModule._

trait Module extends CrossJvmJs {

  final override def jvmDeps = Seq()

  final override def jsDeps = Seq()

  final override def scalacPluginIvyDeps = Agg(ivy"org.sireum::scalac-plugin:$scalacPluginVersion")

  final override def testIvyDeps = Agg(ivy"org.scalatest::scalatest::$scalaTestVersion")

  final override def jvmTestIvyDeps = Agg(ivy"com.sksamuel.diff:diff:$diffVersion")

  final override def jsTestIvyDeps = Agg.empty

  final override def testScalacPluginIvyDeps = scalacPluginIvyDeps

  final override def jvmTestFrameworks = Seq("org.scalatest.tools.Framework")

  final override def jsTestFrameworks = jvmTestFrameworks
}

object Module {

  trait Common extends Module {

    final override def deps = Seq(frontEndObject)

    def frontEndObject: CrossJvmJs

    final override def ivyDeps = Agg.empty
  }

  trait C extends Module {

    final override def deps = Seq(commonObject)

    final override def ivyDeps = Agg.empty

    def commonObject: Common
  }

  trait Cli extends Module {

    final override def deps = Seq(cObject, toolsObject)

    final override def ivyDeps = Agg(ivy"com.lihaoyi::ammonite-ops:$ammoniteOpsVersion")

    def cObject: C

    def toolsObject: CrossJvmJs
  }

}
