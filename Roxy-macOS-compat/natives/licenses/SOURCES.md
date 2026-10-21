# macOS arm64 native payload sources

This directory accompanies the native-only JARs in its parent. Their Java classes and module descriptors were intentionally excluded; the matching Java APIs remain supplied by Voxy and LWJGL.

| Payload source | Maven artifact | SHA-256 of downloaded source artifact | POM license declaration |
| --- | --- | --- | --- |
| [LWJGL LMDB 3.3.3 macOS arm64 natives](https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-lmdb/3.3.3/lwjgl-lmdb-3.3.3-natives-macos-arm64.jar) | `org.lwjgl:lwjgl-lmdb:3.3.3:natives-macos-arm64` | `c4d934abebe76bfbb701750022bd2469859fc9cf206e4703d4f9e09819a13485` | BSD-3-Clause |
| [LWJGL Zstandard 3.3.3 macOS arm64 natives](https://repo.maven.apache.org/maven2/org/lwjgl/lwjgl-zstd/3.3.3/lwjgl-zstd-3.3.3-natives-macos-arm64.jar) | `org.lwjgl:lwjgl-zstd:3.3.3:natives-macos-arm64` | `ee62522e0471babc30e3c9b33c8d176e8930485f618ce7478a9f101890a83889` | BSD-3-Clause |
| [RocksDB JNI 10.2.1](https://repo.maven.apache.org/maven2/org/rocksdb/rocksdbjni/10.2.1/rocksdbjni-10.2.1.jar) | `org.rocksdb:rocksdbjni:10.2.1` | `4358a08bb96652dca35247137ec42db26e6b71f0a02633a3d7508cf2971f2f8b` | Apache-2.0 and GPL-2.0 |

The LWJGL classifier POMs declare BSD-3-Clause. Their bundled native code also carries the LMDB OpenLDAP Public License and Zstandard BSD license; those upstream notices are retained as `LMDB-LICENSE.txt` and `ZSTD-LICENSE.txt`. RocksDB's pinned source tree includes Apache 2.0 and LevelDB notices; the published RocksDB JNI POM additionally lists GPL-2.0. All corresponding texts are retained beside these payloads.

License source files are pinned to [LWJGL 3.3.3](https://github.com/LWJGL/lwjgl3/tree/3.3.3) and [RocksDB v10.2.1](https://github.com/facebook/rocksdb/tree/v10.2.1). Their SHA-256 values are verified by `prepare-natives.sh` before copying.

| License or notice | SHA-256 |
| --- | --- |
| [LWJGL license](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.3/LICENSE.md) | `4c2f770897d0cfda4d9a65bb4d6d6983dccfd57d4c7eb74ea5ded0762cbc0e75` |
| [LMDB license](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.3/modules/lwjgl/lmdb/lmdb_license.txt) | `3bbb1a49c318982a054416eeeee1c15c10666b44d7fcfa7455200836e2a0ee4f` |
| [Zstandard license](https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.3/modules/lwjgl/zstd/zstd_license.txt) | `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8` |
| [RocksDB Apache license](https://raw.githubusercontent.com/facebook/rocksdb/v10.2.1/LICENSE.Apache) | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| [LevelDB license](https://raw.githubusercontent.com/facebook/rocksdb/v10.2.1/LICENSE.leveldb) | `b568f1f37a5a1d37a3e981e7c8b7fc4856ca33b2ca0a45bc8e847aaaf7afe485` |
| [RocksDB GPL-2.0 copy](https://raw.githubusercontent.com/facebook/rocksdb/v10.2.1/COPYING) | `8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643` |
