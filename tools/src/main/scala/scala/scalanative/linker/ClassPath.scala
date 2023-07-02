package scala.scalanative
package linker

import java.nio.file.Path

import scala.collection.mutable
import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir.serialization.deserializeBinary
import scala.scalanative.nir.{Defn, Global}
import scala.scalanative.nir.serialization.{Prelude => NirPrelude}

sealed trait ClassPath {

  /** Check if given global is present in this classpath. */
  private[scalanative] def contains(name: Global): Boolean

  // MEMO: ClassLoaderから使われる
  /** Load given global and info about its dependencies. */
  private[scalanative] def load(name: Global): Option[Seq[Defn]]

  private[scalanative] def classesWithEntryPoints: Iterable[Global.Top]
}

object ClassPath {

  /** Create classpath based on the directory. */
  def apply(directory: Path): ClassPath =
    new Impl(VirtualDirectory.local(directory))

  /** Create classpath based on the virtual directory. */
  private[scalanative] def apply(directory: VirtualDirectory): ClassPath =
    new Impl(directory)

  private final class Impl(directory: VirtualDirectory) extends ClassPath {
    private val files =
      directory.files
        .filter(_.toString.endsWith(".nir"))
        .map { file =>
          val name = Global.Top(io.packageNameFromPath(file))

          name -> file
        }
        .toMap

    private val cache =
      mutable.Map.empty[Global, Option[Seq[Defn]]]

    def contains(name: Global) =
      files.contains(name.top)

    private def makeBufferName(directory: VirtualDirectory, file: Path) =
      directory.uri
        .resolve(new java.net.URI(file.getFileName().toString))
        .toString

    // MEMO: 引数のGlobalがchacheに存在すれbそれを返し、なければファイルの内容をdeseriして返す
    def load(name: Global): Option[Seq[Defn]] =
      cache.getOrElseUpdate(
        name, {
          // MEMO: なんでここでname.topを読んでる？ nameじゃだめ？
          files.get(name.top).map { file =>
            deserializeBinary(
              directory.read(file),
              makeBufferName(directory, file)
            )
          }
        }
      )

    lazy val classesWithEntryPoints: Iterable[Global.Top] = {
      files.filter {
        case (_, file) =>
          val buffer = directory.read(file, len = NirPrelude.length)
          NirPrelude
            .readFrom(buffer, makeBufferName(directory, file))
            .hasEntryPoints
      }.keySet
    }
  }
}
