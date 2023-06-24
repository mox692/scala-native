
# want to
* compileのフェーズを一通り見る
  * nir
  * link
  * optimize
  * codegen
* https://github.com/scala-native/scala-native/pull/3279 を読む
- [ ] ソースを追うための工夫
  - [ ] .hnirを出力するように、sandboxの設定を変更
- [ ] GCの仕組みを見る
  - [ ] いつ、どのようにGCが走る？
    - [ ] GCの対象のリストに追加されるイメージなんだが

# 疑問
* classpath とは何？
  * `tools/src/main/scala/scala/scalanative/build/Config.scala` より、 ` Sequence of all NIR locations. Sequence of all NIR locations.`
* nsc plugin は、どの範囲のファイルの.nirを出力してる？
  * 全てのnativelibとかを出力してそうに見えるが...
  * そしてこれが2回目のcompileの時とかはどうなる？
* nirのGlobalとは？？
  * Global
* Defnとは？
  * nir/src/main/scala/scala/scalanative/nir/Defns.scala にあるが、Const, Trait, Moduleとか、トップレベルの何か (?)
* mainの内容に関係なく、javalibとか全部compile(nirの生成)してない？
  * デフォルトではそれでも良いかもしれないけど、理想的にはもっとplugableであるべきな気がするんだけど、どうだろうか？ (それこそbaremetalとかの応用がしやすそう)
* scala-cliとかmilliとかの対応ってしてるんだっけ？
* unix向けのbuildでもwindowsのコードcompileしてない？

# GCの処理の流れ
* nativelib/src/main/resources/scala-native/gc/boehm/gc.c でCレベルのprimitiveが定義されている. 一部 Boehm GC をそのまま使ってる
* tools/src/main/scala/scala/scalanative/codegen/Lower.scala の genClassallocOp でこれらを呼び出すようなコードを生成していそう
  * ここは Op を見て、具体的なコードを生成する箇所.
  * NirにClassallocがあると、genClassallocOpが働く.
* 実際に hnir を見たら、classallocされてそう
```
  %12 = classalloc @"T34java.lang.IllegalArgumentException"
  %13 = call[(@"T34java.lang.IllegalArgumentException", @"T16java.lang.String") => unit] @"M34java.lang.IllegalArgumentExceptionRL16java.lang.StringE" : ptr(%12 : !?@"T34java.lang.IllegalArgumentException", "Buffer size <= 0")
  throw %12 : !?@"T34java.lang.IllegalArgumentException"
```
* next todo: allocationのコードはわかったので、collectの箇所のソースを読む

# java libのreplace
* concurrentSkipListMap
* 6/12
  * ConcurrentSkipListMap classの private var fieldを移植中. その中でいろんなclassを移植する必要あり.
    * 手始めにKeySetから移植
      * 下記のようなエラーがあって、ちょくちょくgetterを消した
```
[error] /Users/s15255/work/scala-native/javalib/src/main/scala/java/util/concurrent/ConcurrentSkipListMap.scala:2325:16: method keySet is defined twice;
[error]   the conflicting variable keySet was defined at line 1197:15
[error]   override def keySet(): NavigableSet[K] = ???
```

   * それt同時に `keySet` とかを public fieldにした



## want
* SubMapKeyIteratorとかで、Keysetの???を消す
* ConcurrentSkipListMap のprivate valをコメントインしていく


## policy
* Object -> Any
* fieldやmethodを持たないものはcase classに.


# アイデア
* ~~buildのログをdebugで出してあげると良さげな気がする~~
* 

# Annot
* // MEMO: 
* // TODO: 
* // Q: 
* // DEBUG: 


# Issue and PR
* https://github.com/scala-native/scala-native/issues/3341
  * 6/24
    * issueコメントでやらないといけないことはわかった
    * が、下記がよくわかっていない.
      * WeakReferenceIdとはなんなのか？
      * isInstanceOf が `cls.id >= WeakReferenceId && cls.idRangeUnti <= WeakReferenceId` で表現できるのはなぜ？
        * class.idとclass.idRangeUntiの違いは？
          * `nativelib/src/main/resources/scala-native/gc/immix_commix/headers/ObjectHeader.h` に入れたdebugの感じだと、どっちも同じ値を取ることがあるっぽい？
          * 

