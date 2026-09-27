#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <dirent.h>
#include <stdlib.h>


dev_t dev;
static const int sep = 0;

/*
 * Inodes seen so far, so that directories reached twice through bind mounts
 * (e.g. /data/user/0 is /data/data) and hard linked files are counted once.
 */
static ino_t *seen;
static size_t seenCapacity;
static size_t seenCount;

/* Returns 1 if the inode was seen before, otherwise remembers it. */
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

struct Entity {
  long long sizeInBlocks;
  long long sizeInBytes;
  const char *name;
  struct Entity *next;
  char isdir;
};

void scan_dir(const char *path, struct Entity *entity);

const char *getName(const char *path) {
  return strrchr(path, '/') + 1;
}

int nfiles = 0;

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

void dump(char type) {
  putchar(type);
}

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

void dump_error(char type, const char *path,
    long long sizeInBlocks, long long sizeInBytes) {
  printf(get_error(), type, getName(path));
  putchar(sep);
  printf("%lld", sizeInBlocks);
  putchar(sep);
  printf("%lld", sizeInBytes);
  putchar(sep);
}

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
