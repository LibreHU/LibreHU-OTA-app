package org.librehu.ota.install

/**
 * Shell functions giving write access to a read-only partition without remounting the mount Android uses.
 *
 * `mount -o rw,remount /system` fails on this head unit: /system is inside the root filesystem (system-as-root),
 * shown as `/dev/root` in /proc/mounts, a node that does not exist, so the block device Android set read-only
 * (BLKROSET) cannot be made writable through it. And with root modules, /system/priv-app may be a tmpfs of Magisk
 * where a write is lost at the next start. So the real block device (by-name, or the one behind the mount) is mounted
 * again on a private directory, that mount is made read-write and the files are written through it; the partition is
 * put back read-only afterwards. Refused under dm-verity (writing under verity breaks the boot): the user disables it
 * (`fastboot --disable-verity flash vbmeta vbmeta.img`).
 *
 * `rw_part_dev <name>` prints the block device of a partition; `rw_path_dev <path>` sets `RW_DEV` and `RW_IN` (the
 * path inside that filesystem) from the mount holding the path. `rw_open <path>` sets `RW` to the same path through the read-write mount (exit 0), or prints `NODEV`, `VERITY`,
 * `MOUNT` or `READONLY` with the reason (exit 2 to 4). `rw_close` syncs and undoes it.
 */
object RwMount {
    val LIB =
        """
        rw_part_dev() {
          _s=${'$'}(getprop ro.boot.slot_suffix)
          for _d in /dev/block/by-name/${'$'}1${'$'}_s /dev/block/platform/bootdevice/by-name/${'$'}1${'$'}_s \
            /dev/block/platform/*/by-name/${'$'}1${'$'}_s /dev/block/platform/*/*/by-name/${'$'}1${'$'}_s; do
            [ -e "${'$'}_d" ] && { readlink -f "${'$'}_d"; return; }
          done
        }
        rw_path_dev() {
          _best=""; _mm=""; _root=""
          while read -r _id _par _m _r _mp _rest; do
            case "${'$'}1" in "${'$'}_mp"/*|"${'$'}_mp") ;; *) [ "${'$'}_mp" = / ] || continue ;; esac
            [ ${'$'}{#_mp} -ge ${'$'}{#_best} ] && { _best=${'$'}_mp; _mm=${'$'}_m; _root=${'$'}_r; }
          done < /proc/self/mountinfo
          _dn=${'$'}(sed -n 's/^DEVNAME=//p' /sys/dev/block/${'$'}_mm/uevent 2>/dev/null)
          [ -n "${'$'}_dn" ] || return
          RW_DEV="/dev/block/${'$'}_dn"
          if [ "${'$'}_best" = / ]; then RW_IN="${'$'}{_root%/}${'$'}1"; else RW_IN="${'$'}{_root%/}${'$'}{1#${'$'}_best}"; fi
        }
        rw_open() {
          RW=""; RW_MNT=""; RW_DEV=""; RW_IN=""; _part=""
          case "${'$'}1" in
            /system|/system/*|/vendor|/vendor/*|/product|/product/*)
              _part=${'$'}{1#/}; _part=${'$'}{_part%%/*}
              RW_DEV=${'$'}(rw_part_dev ${'$'}_part); RW_IN=${'$'}{1#/${'$'}_part} ;;
          esac
          if [ -z "${'$'}RW_DEV" ]; then
            _part=""
            rw_path_dev "${'$'}1"
          fi
          [ -b "${'$'}RW_DEV" ] || { echo "NODEV ${'$'}1 (${'$'}(grep -E " ${'$'}{1%/*} | / " /proc/mounts | cut -d' ' -f1-4 | tr '\n' ';'))"; return 2; }
          _b=${'$'}{RW_DEV##*/}
          case "${'$'}_b" in dm-*) echo "VERITY ${'$'}RW_DEV"; return 3 ;; esac
          for _sl in /sys/block/dm-*/slaves/${'$'}_b; do
            [ -e "${'$'}_sl" ] && { echo "VERITY ${'$'}{_sl#/sys/block/} -> ${'$'}RW_DEV"; return 3; }
          done
          RW_MNT=/dev/librehu_rw.${'$'}${'$'}
          mkdir -p ${'$'}RW_MNT
          RW_WASRO=${'$'}(blockdev --getro "${'$'}RW_DEV" 2>/dev/null)
          blockdev --setrw "${'$'}RW_DEV" 2>/dev/null
          RW_SBRW=""
          if ! _e=${'$'}(mount -o ro "${'$'}RW_DEV" ${'$'}RW_MNT 2>&1); then
            # Already read-write somewhere (same superblock): mounted read-write directly.
            _e=${'$'}(mount -o rw "${'$'}RW_DEV" ${'$'}RW_MNT 2>&1) || { rmdir ${'$'}RW_MNT; RW_MNT=""; echo "MOUNT ${'$'}RW_DEV ${'$'}_e"; return 4; }
            RW_SBRW=1
          elif ! _e=${'$'}(mount -o remount,rw ${'$'}RW_MNT 2>&1); then
            umount ${'$'}RW_MNT; rmdir ${'$'}RW_MNT; RW_MNT=""
            [ "${'$'}RW_WASRO" = 1 ] && blockdev --setro "${'$'}RW_DEV" 2>/dev/null
            echo "READONLY ${'$'}RW_DEV ${'$'}_e"; return 4
          fi
          # System-as-root: the system image holds /system as a directory.
          if [ "${'$'}_part" = system ] && [ -f ${'$'}RW_MNT/system/build.prop ]; then RW_IN="/system${'$'}RW_IN"; fi
          RW="${'$'}RW_MNT${'$'}RW_IN"
          return 0
        }
        rw_close() {
          [ -n "${'$'}RW_MNT" ] || return 0
          sync
          [ -n "${'$'}RW_SBRW" ] || mount -o remount,ro ${'$'}RW_MNT 2>/dev/null
          umount ${'$'}RW_MNT 2>/dev/null || umount -l ${'$'}RW_MNT 2>/dev/null
          rmdir ${'$'}RW_MNT 2>/dev/null
          [ "${'$'}RW_WASRO" = 1 ] && blockdev --setro "${'$'}RW_DEV" 2>/dev/null
          RW_MNT=""
        }
        """.trimIndent()
}
