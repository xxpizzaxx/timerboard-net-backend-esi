name := "timerboard-net-backend"

version := "1.0"

scalaVersion := "2.12.4"

resolvers += Resolver.jcenterRepo
resolvers += "pizza repo" at "http://dev.pizza.moe/repository/pizza/"
resolvers += Resolver.bintrayRepo("andimiller", "maven")

fork := true
parallelExecution in Test := false

val HTTP4S_VERSION = "0.18.2"

// main dependencies
libraryDependencies ++= Seq(
  // frameworks
  "org.http4s" %% "http4s-core"         % HTTP4S_VERSION,
  "org.http4s" %% "http4s-server"       % HTTP4S_VERSION,
  "org.http4s" %% "http4s-dsl"          % HTTP4S_VERSION,
  "org.http4s" %% "http4s-blaze-server" % HTTP4S_VERSION,
  "org.http4s" %% "http4s-blaze-client" % HTTP4S_VERSION,
  // diff library
  "com.flipkart.zjsonpatch" % "zjsonpatch" % "0.2.3",
  // command line
  "com.github.scopt" %% "scopt" % "3.7.0",
  // metrics
  "io.dropwizard.metrics" % "metrics-core"     % "3.1.2",
  "io.dropwizard.metrics" % "metrics-graphite" % "3.1.2",
  // ESI client
  "eveapi" %% "esi-client" % "1.968.0",
  // logging
  "ch.qos.logback" % "logback-classic" % "1.1.7",
  "ch.qos.logback" % "logback-core"    % "1.1.7",
  // an expiring map for caches
  "net.jodah" % "expiringmap" % "0.5.7"
)

// test frameworks and tools
libraryDependencies ++= Seq(
  "org.scalatest"  %% "scalatest"  % "3.0.5"   % "test",
  "org.mockito"    % "mockito-all" % "1.10.19" % "test"
)

testOptions in Test += Tests
  .Argument(TestFrameworks.ScalaCheck, "-maxSize", "5", "-minSuccessfulTests", "33", "-workers", "1", "-verbosity", "1")

enablePlugins(DockerPlugin)
dockerfile in docker := {
  // The assembly task generates a fat JAR file
  val artifact: File     = assembly.value
  val artifactTargetPath = s"/app/${artifact.name}"

  new Dockerfile {
    from("java")
    add(artifact, artifactTargetPath)
    expose(8080)
    env("xms", "100m")
    env("xmx", "500m")
    env("gc", "-XX:+UseG1GC")
    env("port", "8080")
    env("host", "localhost")
    env("graphite", "")
    cmdRaw(s"""/bin/sh -c "java -Xms$$xms -Xmx$$xmx $$gc -jar $artifactTargetPath --port $$port --host $$host $$graphite $$url"""")
  }
}
