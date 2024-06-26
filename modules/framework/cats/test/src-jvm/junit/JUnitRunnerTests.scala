package weaver
package junit

import cats.Show
import cats.effect._

import org.junit.runner.Description
import org.junit.runner.notification.{ Failure, RunListener, RunNotifier }

object JUnitRunnerTests extends IOSuite {

  type Res = BlockerCompat[IO]
  def sharedResource: Resource[IO, Res] = effectCompat.blocker(identity)

  implicit val showNotifList: Show[List[Notification]] =
    list => list.map(_.toString()).mkString("\n")

  test("Notifications are issued correctly") { blocker =>
    run(blocker, Meta.MySuite).map { notifications =>
      val (failures, filteredNotifs) = notifications.partition {
        case TestFailure(_, _) => true
        case _                 => false
      }
      val failureMessage = failures.collect {
        case TestFailure(_, message) => message
      }

      val expected = List(
        TestSuiteStarted("weaver.junit.Meta$MySuite$"),
        TestStarted("success(weaver.junit.Meta$MySuite$)"),
        TestFinished("success(weaver.junit.Meta$MySuite$)"),
        TestStarted("failure(weaver.junit.Meta$MySuite$)"),
        TestFinished("failure(weaver.junit.Meta$MySuite$)"),
        TestIgnored("ignore(weaver.junit.Meta$MySuite$)"),
        TestSuiteFinished("weaver.junit.Meta$MySuite$")
      )
      expect.same(filteredNotifs, expected) and
        exists(failureMessage)(s =>
          expect(s.contains("oops")) && expect(
            s.contains("JUnitRunnerTests.scala")))
    }
  }

  test("Only tests tagged with only are ran") { blocker =>
    run(blocker, Meta.Only).map { notifications =>
      val expected = List(
        TestSuiteStarted("weaver.junit.Meta$Only$"),
        TestIgnored("not only(weaver.junit.Meta$Only$)"),
        TestStarted("only(weaver.junit.Meta$Only$)"),
        TestFinished("only(weaver.junit.Meta$Only$)"),
        TestSuiteFinished("weaver.junit.Meta$Only$")
      )
      expect.same(notifications, expected)
    }
  }

  test("Only tests tagged with only are ran (unless also tagged ignored)") {
    blocker =>
      run(blocker, Meta.IgnoreAndOnly).map { notifications =>
        val expected = List(
          TestSuiteStarted("weaver.junit.Meta$IgnoreAndOnly$"),
          TestIgnored("only and ignored(weaver.junit.Meta$IgnoreAndOnly$)"),
          TestIgnored("is ignored(weaver.junit.Meta$IgnoreAndOnly$)"),
          TestIgnored("not tagged(weaver.junit.Meta$IgnoreAndOnly$)"),
          TestStarted("only(weaver.junit.Meta$IgnoreAndOnly$)"),
          TestFinished("only(weaver.junit.Meta$IgnoreAndOnly$)"),
          TestSuiteFinished("weaver.junit.Meta$IgnoreAndOnly$")
        )
        expect.same(notifications, expected)
      }
  }

  test("Tests tagged with ignore are ignored") { blocker =>
    run(blocker, Meta.Ignore).map { notifications =>
      val expected = List(
        TestSuiteStarted("weaver.junit.Meta$Ignore$"),
        TestIgnored("is ignored(weaver.junit.Meta$Ignore$)"),
        TestStarted("not ignored 1(weaver.junit.Meta$Ignore$)"),
        TestFinished("not ignored 1(weaver.junit.Meta$Ignore$)"),
        TestStarted("not ignored 2(weaver.junit.Meta$Ignore$)"),
        TestFinished("not ignored 2(weaver.junit.Meta$Ignore$)"),
        TestSuiteFinished("weaver.junit.Meta$Ignore$")
      )
      expect.same(notifications, expected)
    }
  }

  test("Works when suite asks for global resources") {
    blocker =>
      run(blocker, classOf[Meta.Sharing]).map { notifications =>
        val expected = List(
          TestSuiteStarted("weaver.junit.Meta$Sharing"),
          TestStarted("foo(weaver.junit.Meta$Sharing)"),
          TestFinished("foo(weaver.junit.Meta$Sharing)"),
          TestSuiteFinished("weaver.junit.Meta$Sharing")
        )
        expect.same(notifications, expected)
      }
  }

  def run(
      blocker: BlockerCompat[IO],
      suite: Class[_]): IO[List[Notification]] = for {
    runner   <- IO(new WeaverRunner(suite))
    queue    <- IO(scala.collection.mutable.Queue.empty[Notification])
    notifier <- IO(new RunNotifier())
    _        <- IO(notifier.addListener(new NotificationListener(queue)))
    _        <- blocker.block(runner.run(notifier))
  } yield queue.toList

  def run(
      blocker: BlockerCompat[IO],
      suite: SimpleIOSuite): IO[List[Notification]] =
    run(blocker, suite.getClass())

  sealed trait Notification
  case class TestSuiteStarted(name: String)             extends Notification
  case class TestAssumptionFailure(failure: Failure)    extends Notification
  case class TestFailure(name: String, message: String) extends Notification
  case class TestFinished(name: String)                 extends Notification
  case class TestIgnored(name: String)                  extends Notification
  case class TestStarted(name: String)                  extends Notification
  case class TestSuiteFinished(name: String)            extends Notification

  class NotificationListener(
      queue: scala.collection.mutable.Queue[Notification])
      extends RunListener {
    override def testSuiteStarted(description: Description): Unit =
      queue += TestSuiteStarted(description.getDisplayName())
    override def testAssumptionFailure(failure: Failure): Unit =
      queue += TestAssumptionFailure(failure)
    override def testFailure(failure: Failure): Unit =
      queue += TestFailure(failure.getDescription.getDisplayName,
                           failure.getMessage())
    override def testFinished(description: Description): Unit =
      queue += TestFinished(description.getDisplayName())
    override def testIgnored(description: Description): Unit =
      queue += TestIgnored(description.getDisplayName())
    override def testStarted(description: Description): Unit =
      queue += TestStarted(description.getDisplayName())
    override def testSuiteFinished(description: Description): Unit =
      queue += TestSuiteFinished(description.getDisplayName())
  }

}

object Meta {

  object MySuite extends SimpleIOSuite {

    override def maxParallelism: Int = 1

    pureTest("success") {
      success
    }

    pureTest("failure") {
      failure("oops")
    }

    test("ignore") {
      ignore("just because")
    }

  }

  object Only extends SimpleIOSuite {

    override def maxParallelism: Int = 1

    pureTest("only".only) {
      success
    }

    pureTest("not only") {
      failure("foo")
    }

  }

  object Ignore extends SimpleIOSuite {

    override def maxParallelism: Int = 1

    pureTest("not ignored 1") {
      success
    }

    pureTest("not ignored 2") {
      success
    }

    pureTest("is ignored".ignore) {
      failure("foo")
    }

  }

  object IgnoreAndOnly extends SimpleIOSuite {

    override def maxParallelism: Int = 1

    pureTest("only".only) {
      success
    }

    pureTest("not tagged") {
      failure("foo")
    }

    pureTest("only and ignored".only.ignore) {
      failure("foo")
    }

    pureTest("is ignored".ignore) {
      failure("foo")
    }

  }

  class Sharing(global: GlobalRead) extends IOSuite {

    type Res = Unit
    // Just checking the suite does not crash
    def sharedResource: Resource[IO, Unit] = global.getR[Int]().map(_ => ())

    pureTest("foo") {
      success
    }

  }

}
