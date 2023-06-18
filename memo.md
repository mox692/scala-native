
# want to
* compileのフェーズを一通り見る
  * nir
  * link
  * optimize
  * codegen
* https://github.com/scala-native/scala-native/pull/3279 を読む
- [ ] ソースを追うための工夫
  - [ ] .hnirを出力するように、sandboxの設定を変更
- [ ] 

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


# アイデア
* ~~buildのログをdebugで出してあげると良さげな気がする~~
* 

# Annot
* // MEMO: 
* // TODO: 
* // Q: 
* // DEBUG: 

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

