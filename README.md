DiskUsage
=========

日本語 | [English](README.en.md)

ストレージの容量を大きく消費しているファイルやディレクトリを見つけるための Android アプリです。

ディレクトリの大きさに比例した図で、数階層分のサブディレクトリをまとめて表示します。
図を拡大して特定のディレクトリの中身を確認し、不要なファイルを見つけて削除できます。
一般的なファイルマネージャーではなく、容量を圧迫しているものを探して片付けるためのアプリです。

<img src="extra/screenshot-ja.png" width="360" alt="スクリーンショット">

## このフォークについて

[Ivan Volosyuk 氏の DiskUsage](https://github.com/IvanVolosyuk/diskusage) を
[WhiredPlanck 氏がメンテナンスしていたフォーク](https://github.com/WhiredPlanck/diskusage)
を元に、最新の Android で動くように更新したものです。主な変更点:

- Android 17 (API 37) に対応。16 KB ページサイズの端末でも動作します。
- 高速なネイティブスキャナを修正し、再び使われるようにしました。
- 日本語に対応し、既存の翻訳の不足も補いました。
- 全体を Kotlin で書き直し、描画をハードウェアアクセラレーションの Canvas に変更しました。

変更の詳細は [リリース](https://github.com/taketake5656/diskusage/releases) をご覧ください。

## インストール

[リリース](https://github.com/taketake5656/diskusage/releases) から最新の APK をダウンロードしてインストールしてください。

- 動作環境: Android 6.0 (API 23) 以降
- 他の DiskUsage とは署名が異なるため、インストール済みの場合はアンインストールしてからインストールしてください。

## 権限

| 権限 | 用途 |
| --- | --- |
| すべてのファイルへのアクセス | ストレージ内のファイルとディレクトリの大きさを調べ、削除するため |
| 使用状況へのアクセス | アプリごとのデータやキャッシュの容量を表示するため(任意) |

初回の表示時に、必要な権限を順番に確認します。
root 化された端末では、すべてのマウントポイントを表示することもできます。

## 使い方

- ストレージを選ぶとスキャンが始まり、使用状況が図で表示されます。
- ディレクトリをタップすると拡大し、もう一度タップすると全体表示に戻ります。
  ピンチ操作でも拡大・縮小できます。
- メニューから、選択したファイルやディレクトリを他のアプリで開いたり、削除したりできます。
- 虫眼鏡アイコンから、名前でファイルやディレクトリを検索できます。
- アプリの表示言語は、システムの設定(設定 > アプリ > DiskUsage > 言語)から変更できます。

## ビルド

必要なもの:

- JDK 21 と JDK 17(見つからない場合は Gradle が自動でダウンロードします)
- Android SDK Platform 37、NDK 29.0.14206865、CMake 4.1.2

```sh
./gradlew assembleDebug        # デバッグ版 APK
./gradlew testDebugUnitTest    # ユニットテスト
```

リリース版の署名には、`~/.gradle/gradle.properties` などに次の Gradle プロパティを設定します。
設定がない場合、リリース版は署名なしでビルドされます。

```properties
diskusageKeystoreFile=/path/to/release.jks
diskusageKeystorePassword=...
diskusageKeyAlias=...
diskusageKeyPassword=...
```

GitHub Actions では push ごとにビルド・テスト・lint を行い、`v*` タグの push で署名済み APK を添付したリリースを作成します。

## ライセンス

[GNU General Public License v2.0](COPYING.txt)(またはそれ以降のバージョン)
