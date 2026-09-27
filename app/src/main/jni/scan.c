/*
 * DiskUsage - displays sdcard usage on android.
 * Copyright (C) 2008-2011 Ivan Volosyuk
 * Copyright (C) 2022 WhiredPlanck
 * Copyright (C) 2026 taketake5656
 *   2026: modified (see the Git history)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

/**
 * @file scan.c
 * @brief ネイティブスキャナ(libscan.so)。ディレクトリ以下を走査し、サイズを標準出力に書く。
 *
 * アプリから実行ファイルとして起動する(root が必要なときは su 経由)。出力の形式は
 * NativeScanner.kt を参照。同じファイルシステム上の項目だけを数える。
 */
#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <dirent.h>
#include <stdlib.h>


/** @brief スキャンするファイルシステムのデバイス番号(別のデバイスの項目は数えない)。 */
dev_t dev;
/** @brief 出力のフィールドの区切り。 */
static const int sep = 0;

/**
 * @brief これまでに見た inode の集合(オープンアドレス法のハッシュ表、0 は空き)。
 *
 * バインドマウントで 2 回たどり着くディレクトリ(例: /data/user/0 は /data/data)や
 * ハードリンクされたファイルを 1 回だけ数えるために使う。
 */
static ino_t *seen;
/** @brief seen の容量(2 のべき乗)。 */
static size_t seenCapacity;
/** @brief seen に入っている inode の数。 */
static size_t seenCount;

/**
 * @brief inode を既に見たかを調べ、初めてなら覚える。
 *
 * 使用率が半分に達したら表を 2 倍に広げる(メモリが足りなければ覚えずに 0 を返す)。
 *
 * @param ino inode 番号
 * @return 既に見ていれば 1、初めてなら 0
 */
static int check_seen(ino_t ino) {
  if (seenCount * 2 >= seenCapacity) {
    size_t oldCapacity = seenCapacity;
    ino_t *old = seen;
    seenCapacity = oldCapacity ? oldCapacity * 2 : 4096;
    seen = calloc(seenCapacity, sizeof(ino_t));
    if (seen == NULL) {
      seen = old;
      seenCapacity = oldCapacity;
      return 0;
    }
    seenCount = 0;
    for (size_t i = 0; i < oldCapacity; i++) {
      if (old[i] != 0) check_seen(old[i]);
    }
    free(old);
  }
  /* Inode 0 isn't used by filesystems, so it marks empty slots. */
  if (ino == 0) return 0;
  size_t i = (size_t) (ino * 0x9E3779B97F4A7C15ULL) & (seenCapacity - 1);
  while (seen[i] != 0) {
    if (seen[i] == ino) return 1;
    i = (i + 1) & (seenCapacity - 1);
  }
  seen[i] = ino;
  seenCount++;
  return 0;
}

/** @brief 走査中の項目(ディレクトリの子を後で走査するため連結リストにする)。 */
struct Entity {
  long long sizeInBlocks; /**< 512 バイト単位のブロック数 */
  long long sizeInBytes;  /**< バイト数 */
  const char *name;       /**< 名前(malloc したもの) */
  struct Entity *next;    /**< 次の項目 */
  char isdir;             /**< ディレクトリなら 1 */
};

void scan_dir(const char *path, struct Entity *entity);

/**
 * @brief パスの最後の要素(名前)を返す。
 * @param path `/` を含むパス
 * @return path の中の名前の先頭
 */
const char *getName(const char *path) {
  return strrchr(path, '/') + 1;
}

/** @brief 出力した項目の数(10 件ごとに出力をフラッシュする)。 */
int nfiles = 0;

/**
 * @brief 項目を `D` または `F` の形式で出力する。
 * @param entity 出力する項目
 */
void dump_file(struct Entity *entity) {
  if (entity->isdir) {
    putchar('D');
  } else {
    putchar('F');
  }
  printf("%s", entity->name);
  putchar(sep);
  printf("%lld", entity->sizeInBlocks);
  putchar(sep);
  printf("%lld", entity->sizeInBytes);
  putchar(sep);
  nfiles++;
  if (nfiles % 10 == 0) fflush(stdout);
}

/**
 * @brief 1 文字の記号を出力する(`Z` でディレクトリの終わり)。
 * @param type 出力する文字
 */
void dump(char type) {
  putchar(type);
}

/**
 * @brief errno に応じて、読めないディレクトリの名前に付ける書式を返す。
 * @return printf の書式(種類の文字と名前を受け取る)
 */
const char *get_error() {
  switch(errno) {
    case EACCES:
      return "%c%s <No access>";
    case ENOENT:
    case ENOTDIR:
      return "%c%s <deleted>";
    default:
      return "%c%s <error>";
  }
}

/**
 * @brief 読めない項目を、名前に理由を付けて出力する。
 * @param type 種類の文字(`D` など)
 * @param path 項目のパス
 * @param sizeInBlocks ブロック数
 * @param sizeInBytes バイト数
 */
void dump_error(char type, const char *path,
    long long sizeInBlocks, long long sizeInBytes) {
  printf(get_error(), type, getName(path));
  putchar(sep);
  printf("%lld", sizeInBlocks);
  putchar(sep);
  printf("%lld", sizeInBytes);
  putchar(sep);
}

/**
 * @brief stat の結果から項目を作る。
 * @param path 項目のパス
 * @param stbuf stat の結果
 * @return 作成した項目(呼び出し側で解放する)
 */
struct Entity *make_entity_internal(
    const char *path,
    struct stat *stbuf) {
  struct Entity *e = malloc(sizeof(struct Entity));
  e->name = strdup(getName(path));
  e->sizeInBlocks = stbuf->st_blocks;
  e->sizeInBytes = stbuf->st_size;
  e->isdir = S_ISDIR(stbuf->st_mode);
  return e;
}

/**
 * @brief パスの項目を作る。数えない項目なら NULL を返す。
 *
 * 別のデバイス上の項目と、既に見たディレクトリやハードリンクは数えない。
 *
 * @param path 項目のパス
 * @return 作成した項目。lstat に失敗したときや数えないときは NULL
 */
struct Entity *make_entity(const char *path) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res < 0) {
    return NULL;
  }
  if (stbuf.st_dev != dev) {
    return NULL;
  }
  if ((S_ISDIR(stbuf.st_mode) || stbuf.st_nlink > 1) && check_seen(stbuf.st_ino)) {
    return NULL;
  }
  return make_entity_internal(path, &stbuf);
}

/**
 * @brief ディレクトリのパスと名前をつないだパスを作る。
 * @param base ディレクトリのパス
 * @param name 名前
 * @return 新しいパス(呼び出し側で解放する)
 */
char *makePath(const char *base, const char *name) {
  int baseLen = strlen(base);
  int nameLen = strlen(name);
  char *res = (char*) malloc(baseLen + nameLen + 2);
  strncpy(res, base, baseLen);
  res[baseLen] = '/';
  strncpy(res + baseLen + 1, name, nameLen);
  res[baseLen + 1 + nameLen] = 0;
  return res;
}

/**
 * @brief ディレクトリを出力し、その中を再帰的に走査する。
 *
 * ファイルはすぐに出力し、サブディレクトリはディレクトリを閉じてから順に走査する
 * (開いたままのディレクトリの数を抑えるため)。最後に `Z` を出力する。
 *
 * @param path ディレクトリのパス
 * @param dirEntity ディレクトリの項目(開けなければ理由付きの名前で出力する)
 */
void scan_dir(const char *path, struct Entity *dirEntity) {
  DIR *dir = opendir(path);
  struct Entity *e;
  struct Entity *curr;
  struct Entity *prev;
  struct Entity *first;
  struct Entity **last = &first;

  if (dir == NULL) {
    dump_error('D', path, dirEntity->sizeInBlocks,
        dirEntity->sizeInBytes);
    dump('Z');
    return;
  }
  dump_file(dirEntity);

  struct dirent *entity;
  while ((entity = readdir(dir)) != NULL) {
    if (entity->d_name[0] == 0 || (entity->d_name[0] == '.' && (
          entity->d_name[1] == 0 || (
            entity->d_name[1] == '.' && entity->d_name[2] == 0))
        )) continue;
    const char *entityPath = makePath(path, entity->d_name);
    struct Entity *e = make_entity(entityPath);
    free((void*)entityPath);
    if (e == NULL) continue;
    if (!e->isdir) {
      dump_file(e);
      free((void*)e->name);
      free(e);
      continue;
    }
    *last = e;
    last = &(e->next);
  }
  *last = NULL;

  closedir(dir);

  curr = first;

  while (curr != NULL) {
    const char *entityPath = makePath(path, curr->name);
    scan_dir(entityPath, curr);
    free((void*)entityPath);
    free((void*)curr->name);
    prev = curr;
    curr = curr->next;
    free(prev);
  }
  dump('Z');
}

/**
 * @brief ルートのディレクトリを走査する。
 * @param path ルートのパス(このデバイス上の項目だけを数える)
 */
void scan_tree(const char *path) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res == -1) {
    dump_error('D', path, 1, 0);
    dump('Z');
    return;
  }
  dev = stbuf.st_dev;
  check_seen(stbuf.st_ino);
  scan_dir(path, make_entity_internal(path, &stbuf));
}

/**
 * @brief エントリポイント。開始の 0 バイトを出力してから走査する。
 * @param argc 引数の数
 * @param argv argv[1] にスキャンするディレクトリの絶対パス
 * @return 終了コード
 */
int main(int argc, char **argv) {
  if (argv[1] == 0) {
    printf("Need directory argument\n");
    exit(1);
  }
  if (strchr(argv[1], '/') == NULL) {
    printf("Need absolute path\n");
    exit(1);
  }
  putchar(0);
  scan_tree(argv[1]);
}
