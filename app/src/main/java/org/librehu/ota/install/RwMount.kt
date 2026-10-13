package org.librehu.ota.install

/**
 * Shell functions giving write access to a read-only partition without remounting the mount Android uses.
 *
 * `mount -o rw,remount /system` fails on this head unit: /system is inside the root filesystem (system-as-root),
 * shown as `/dev/root` in /proc/mounts, a node that does not exist, so the block device Android set read-only
 * (BLKROSET) cannot be made writable through it. So, first, as `adb remount` does: the real block device (by-name)
 * made writable, then the mount Android uses (/ in system-as-root) remounted read-write in place, kept only when a
 * test file can be written. Else (and with root modules, whose tmpfs over /system/priv-app would lose the write) the
 * block device is mounted again on a private directory made read-write (on the UJC201 that second mount gave I/O
 * errors where `adb remount` works). Put back read-only afterwards. Refused under dm-verity (writing under verity breaks the boot): the user disables it
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
          RW_DEV="/dev/block/${'$'}_dn"; RW_MP=${'$'}_best
          if [ "${'$'}_best" = / ]; then RW_IN="${'$'}{_root%/}${'$'}1"; else RW_IN="${'$'}{_root%/}${'$'}{1#${'$'}_best}"; fi
        }
        rw_open() {
          RW=""; RW_MNT=""; RW_DEV=""; RW_IN=""; RW_MP=""; RW_INPLACE=""; _part=""; _pdev=""
          case "${'$'}1" in
            /system|/system/*|/vendor|/vendor/*|/product|/product/*)
              _part=${'$'}{1#/}; _part=${'$'}{_part%%/*}
              _pdev=${'$'}(rw_part_dev ${'$'}_part) ;;
          esac
          # The mount holding the path now (device and mount point), as Android uses it.
          rw_path_dev "${'$'}1"
          _mdev=${'$'}RW_DEV
          if [ -n "${'$'}_pdev" ]; then
            RW_DEV=${'$'}_pdev; RW_IN=${'$'}{1#/${'$'}_part}
          else
            _part=""
          fi
          [ -b "${'$'}RW_DEV" ] || { echo "NODEV ${'$'}1 (${'$'}(grep -E " ${'$'}{1%/*} | / " /proc/mounts | cut -d' ' -f1-4 | tr '\n' ';'))"; return 2; }
          _b=${'$'}{RW_DEV##*/}
          case "${'$'}_b" in dm-*) echo "VERITY ${'$'}RW_DEV"; return 3 ;; esac
          for _sl in /sys/block/dm-*/slaves/${'$'}_b; do
            [ -e "${'$'}_sl" ] && { echo "VERITY ${'$'}{_sl#/sys/block/} -> ${'$'}RW_DEV"; return 3; }
          done
          RW_WASRO=${'$'}(blockdev --getro "${'$'}RW_DEV" 2>/dev/null)
          blockdev --setrw "${'$'}RW_DEV" 2>/dev/null
          # 1) As adb remount (BLKROSET 0, then MS_REMOUNT|MS_BIND and MS_REMOUNT): the mount Android uses (/ in
          #    system-as-root) remounted read-write in place, kept only
          #    when a test file can really be written (not a tmpfs of root modules: other device, skipped).
          if [ -n "${'$'}RW_MP" ] && [ "${'$'}(readlink -f "${'$'}_mdev")" = "${'$'}(readlink -f "${'$'}RW_DEV")" ] &&
            { mount -o remount,bind,rw "${'$'}RW_MP" 2>/dev/null; mount -o remount,rw "${'$'}RW_MP" 2>/dev/null; }; then
            _t=${'$'}1; [ -d "${'$'}_t" ] || _t=${'$'}(dirname "${'$'}_t")
            if touch "${'$'}_t/.librehu_rw" 2>/dev/null && rm -f "${'$'}_t/.librehu_rw"; then
              RW_INPLACE=${'$'}RW_MP; RW="${'$'}1"
              return 0
            fi
            mount -o remount,ro "${'$'}RW_MP" 2>/dev/null
          fi
          # 2) A private second mount of the block device, made read-write.
          RW_MNT=/dev/librehu_rw.${'$'}${'$'}
          mkdir -p ${'$'}RW_MNT
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
          if [ -n "${'$'}RW_INPLACE" ]; then
            sync
            # Back to read-only (stays read-write until the next start if a file is still open for writing).
            mount -o remount,ro "${'$'}RW_INPLACE" 2>/dev/null
            [ "${'$'}RW_WASRO" = 1 ] && blockdev --setro "${'$'}RW_DEV" 2>/dev/null
            RW_INPLACE=""
            return 0
          fi
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
