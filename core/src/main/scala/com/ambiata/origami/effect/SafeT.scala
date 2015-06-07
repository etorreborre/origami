package com.ambiata.origami
package effect

import scalaz._, Scalaz._, effect._
import NonEmptyList._
import collection.JavaConverters._
import SafeT._
import collection.mutable.ListBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Monad transformer for "safe" resources
 */
case class SafeT[M[_], A](private val exec: ConcurrentLinkedQueue[Finalizer[M]] => M[A]) {
  type Finalizers = ConcurrentLinkedQueue[Finalizer[M]]

  private def finalizeT(finalizers: Finalizers)(implicit m: Monad[M], c: Catchable[M]): M[List[Throwable]] = {
    finalizers.asScala.toList.foldLeft(m.point(List[Throwable]())) { (errors, f) =>
      c.attempt(f.run) >>= { a =>
        a.fold(
          l => errors.map(_ :+ l),
          r => errors
        )
      }
    }
  }

  def run(implicit m: Monad[M], c: Catchable[M]): M[NonEmptyList[Throwable] \/ (A, List[Throwable])] =
    runSafeT(new Finalizers)

  def runSafeT(finalizers: Finalizers)(implicit m: Monad[M], c: Catchable[M]): M[NonEmptyList[Throwable] \/ (A, List[Throwable])] = {
    c.attempt(exec(finalizers)) >>= { a =>
      val finalized = finalizeT(finalizers)
      a.fold(
        l => finalized.map(errors => -\/(nel(l, errors))),
        r => finalized.map(errors => \/-((r, errors)))
      )
    }
  }

  def flatMap[B](f: A => SafeT[M, B])(implicit m: Monad[M], c: Catchable[M]): SafeT[M, B] =
    SafeT[M, B] { finalizers =>
      m.bind(exec(finalizers)) { a =>
        f(a).exec(finalizers)
      }
    }

  def `finally`(after: M[Unit])(implicit m: Monad[M], c: Catchable[M]): SafeT[M, A] =
    SafeT[M, A](finalizers => { finalizers add Finalizer(after); exec(finalizers) })
}

object SafeT { outer =>

  def `finally`[M[_] : Monad : Catchable, A](action: M[A])(after: M[Unit]): SafeT[M, A] =
    bracket(Monad[M].point(()))(_ => action)(_ => after)

  implicit class Finally[M[_] : Monad : Catchable, A](action: M[A]) {
    def `finally`(after: M[Unit]): SafeT[M, A] =
      outer.`finally`(action)(after)
  }

  def bracket[M[_] : Monad : Catchable, A, B, C](acquire: M[A])(step: A => M[B])(release: A => M[C]): SafeT[M, B] =
    for {
      a <- lift(acquire)
      b <- lift(step(a)) `finally` release(a).void
    } yield b

  def bracket_[M[_] : Monad : Catchable, A, B, C](before: M[A])(action: M[B])(after: M[C]): SafeT[M, B] =
    bracket(before)(_ => action)(_ => after)

  def lift[M[_] : Monad : Catchable, A](ma: M[A]) =
    SafeT[M, A](_ => ma)

  implicit def SafeTMonad[M[_] : Monad : Catchable]: Monad[SafeT[M, ?]] = new Monad[SafeT[M, ?]] {
    def point[A](a: =>A): SafeT[M, A] =
      SafeT[M, A](_ => Monad[M].point(a))

    def bind[A, B](st: SafeT[M, A])(f: A => SafeT[M, B]): SafeT[M, B] =
      st.flatMap(f)
  }

  implicit def SafeTCatchable[M[_] : Monad : Catchable]: Catchable[SafeT[M, ?]] = new Catchable[SafeT[M, ?]] {
    def attempt[A](action: SafeT[M, A]): SafeT[M, Throwable \/ A] =
      SafeT[M, Throwable \/ A](finalizers => Catchable[M].attempt(action.exec(finalizers)))

    def fail[A](throwable: Throwable): SafeT[M, A] =
      SafeT[M, A](_ => Catchable[M].fail(throwable))
  }
}

trait Finalizer[M[_]] {
  def run: M[Unit]
}

object Finalizer {
  def apply[M[_]](r: M[Unit]) = new Finalizer[M] {
    def run = r
  }
}