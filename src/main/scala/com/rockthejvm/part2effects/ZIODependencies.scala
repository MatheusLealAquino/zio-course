package com.rockthejvm.part2effects

import zio._

import java.util.concurrent.TimeUnit

object ZIODependencies extends ZIOAppDefault {
  // app to subscribe users to newsletter
  case class User(name: String, email: String)

  class UserSubscription(emailService: EmailService, userDatabase: UserDatabase) {
    def subscribeUser(user: User): Task[Unit] =
      for {
        _ <- emailService.email(user)
        _ <- userDatabase.insert(user)
      } yield ()
  }

  object UserSubscription {
    def create(emailService: EmailService, userDatabase: UserDatabase) =
      new UserSubscription(emailService, userDatabase)

    val live: ZLayer[EmailService with UserDatabase, Nothing, UserSubscription] =
      ZLayer.fromFunction(create _)
  }

  object EmailService {
    def create(): EmailService = new EmailService

    val live: ZLayer[Any, Nothing, EmailService] =
      ZLayer.succeed(create())
  }

  class EmailService {
    def email(user: User): Task[Unit] =
      ZIO.succeed(println(s"Welcome to rock the jvm, ${user.name}"))
  }

  object UserDatabase {
    def create(connectionPool: ConnectionPool) =
        new UserDatabase(connectionPool)

    val live: ZLayer[ConnectionPool, Nothing, UserDatabase] =
      ZLayer.fromFunction(create _)
  }

  class UserDatabase(connectionPool: ConnectionPool) {
    def insert(user: User): Task[Unit] = for {
      conn <- connectionPool.get
      _ <- conn.runQuery(s"insert into subscribers(name, email) values (${user.name}, ${user.email})")
    } yield ()
  }

  object ConnectionPool {
    def create(nConnections: Int) =
        new ConnectionPool(nConnections)

    def live(nConnections: Int): ZLayer[Any, Nothing, ConnectionPool] =
      ZLayer.succeed(create(nConnections))
  }

  class ConnectionPool(nConnections: Int) {
    def get: Task[Connection] =
          ZIO.succeed(println("Acquired connection")) *> ZIO.succeed(Connection())
//      for {
//        _ <- ZIO.succeed(println("Acquired connection"))
//        conn <- ZIO.succeed(Connection())
//      } yield conn

  }
  case class Connection() {
    def runQuery(query: String): Task[Unit] =
      ZIO.succeed(println(s"Executing query: $query"))
  }

  val subscriptionService = ZIO.succeed( // Dependency injection
    UserSubscription.create(
      EmailService.create(),
      UserDatabase.create(
        ConnectionPool.create(10)
      )
    )
  )

  /*
    "clean DI" has drawbacks
      - does not scale for many services
      - DI can be 100x worse
        - pass dependencies partially
        - not having all deps in the same place
        - passing dependencies multiple times
   */

  def subscribe(user: User) = for {
    sub <- subscriptionService // service is instantiated at the point of call
    _ <- sub.subscribeUser(user)
  } yield ()

  // risk leaking resources if you subscribe multiple users in the same program
  val program = for {
    _ <- subscribe(User("Kiedis", "Kiedis@gmail.com"))
    _ <- subscribe(User("Flea", "flea @gmail.com"))
  } yield ()

  // alternative
  def subscribe_v2(user: User) = for {
    sub <- ZIO.service[UserSubscription] // ZIO[UserSubscription, Nothing, UserSubscription]
    _ <- sub.subscribeUser(user)
  } yield ()

  val program_v2 = for {
    _ <- subscribe_v2(User("Kiedis", "Kiedis@gmail.com"))
    _ <- subscribe_v2(User("Flea", "flea @gmail.com"))
  } yield ()

  /*
    - we don't need to care about dependencies until the end of the world
    - all ZIOs requiring this dependency will use the same instance
    - can use different instances of the same type for different needs (e.g. testing)
    - layers can be created and composed much like regular ZIOs + rich API
   */

  /**
   * ZLayers
   */
  val connectionPoolLayer: ZLayer[Any, Nothing, ConnectionPool] = ZLayer.succeed(ConnectionPool.create(10))
  // a layer that requires a dependency (higher layer) can be built with ZLayer.fromFunction
  // (and automatically fetch the function arguments and place then into the ZLayer's dependency/environment type argument)
  val databaseLayer: ZLayer[ConnectionPool, Nothing, UserDatabase] = ZLayer.fromFunction(UserDatabase.create _)
  val emailServiceLayer: ZLayer[Any, Nothing, EmailService] = ZLayer.succeed(EmailService.create())
  val userSubscriptionServiceLayer: ZLayer[UserDatabase with EmailService, Nothing, UserSubscription] =
    ZLayer.fromFunction(UserSubscription.create _)

  // composing layers
  // vertical composition >>>
  val databaseLayerFull: ZLayer[Any, Any, UserDatabase] = connectionPoolLayer >>> databaseLayer
  // horizontal composition: combines the dependencies of both layers AND the values of both layers
  val subscriptionRequirementsLayer: ZLayer[Any, Any, UserDatabase with EmailService] = databaseLayerFull ++ emailServiceLayer
  // mix & match
  val userSubscriptionLayer: ZLayer[Any, Any, UserSubscription] =
    subscriptionRequirementsLayer >>> userSubscriptionServiceLayer

  // best practice: write "factory" methods exposing layers in the companion objects of the service
  val runnableProgram = program_v2.provide(userSubscriptionLayer)

  // magic
  val runnableProgram_v2 = program_v2.provide(
    UserSubscription.live,
    EmailService.live,
    UserDatabase.live,
    ConnectionPool.live(10),
    // ZIO will tell you if you're missing a layer
    // and if you have multiple layers of the same type
//    ZLayer.Debug.tree,
    ZLayer.Debug.mermaid
  )

  // magic v2
  val userSubscriptionLayer_v2 = ZLayer.make[UserSubscription](
    UserSubscription.live,
    EmailService.live,
    UserDatabase.live,
    ConnectionPool.live(10),
  )

  // passthrough
  val dbWithPollLayer: ZLayer[ConnectionPool, Nothing, ConnectionPool with UserDatabase] = UserDatabase.live.passthrough
  // service = take a dep and expose it as a value to further layers
  val dbService = ZLayer.service[UserDatabase]
  // launch = creates a ZIO that uses the services and never finishes
  val subscriptionLaunch: ZIO[EmailService with UserDatabase, Nothing, Nothing] = UserSubscription.live.launch
  // memorization

  /*
     Already provided services: Clock,Random, System, Console
   */
  val getTime = Clock.currentTime(TimeUnit.SECONDS)
  val randomValue = Random.nextInt
  val sysVariable = System.env("HADOOP_HOME")
  val printlnEffect = Console.printLine("This is ZIO")

  def run = program_v2.provide(userSubscriptionLayer_v2)

//  v1:
//  program_v2.provide(
//    ZLayer.succeed(
//      UserSubscription.create(
//        EmailService.create(),
//        UserDatabase.create(
//          ConnectionPool.create(10)
//        )
//      )
//    )
//  )
}
