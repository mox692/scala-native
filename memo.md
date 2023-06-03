
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
