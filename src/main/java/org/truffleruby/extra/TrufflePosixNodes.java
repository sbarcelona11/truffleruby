/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.extra;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import jnr.constants.platform.Fcntl;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.builtins.CoreClass;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.time.GetTimeZoneNode;
import org.truffleruby.language.SnippetNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.objects.AllocateObjectNode;
import org.truffleruby.extra.ffi.Pointer;
import org.truffleruby.platform.signal.Signal;

import static org.truffleruby.core.string.StringOperations.decodeUTF8;

@CoreClass("Truffle::POSIX")
public abstract class TrufflePosixNodes {

    @TruffleBoundary
    private static void invalidateENV(String name) {
        if (name.equals("TZ")) {
            GetTimeZoneNode.invalidateTZ();
        }
    }

    @CoreMethod(names = "access", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class AccessNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int access(DynamicObject path, int mode) {
            final String pathString = decodeUTF8(path);
            return posix().access(pathString, mode);
        }

    }

    @CoreMethod(names = "chmod", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class ChmodNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int chmod(DynamicObject path, int mode) {
            return posix().chmod(decodeUTF8(path), mode);
        }

    }

    @CoreMethod(names = "chown", isModuleFunction = true, required = 3, lowerFixnum = { 2, 3 })
    public abstract static class ChownNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int chown(DynamicObject path, int owner, int group) {
            return posix().chown(decodeUTF8(path), owner, group);
        }

    }

    @CoreMethod(names = "dup", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class DupNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int dup(int descriptor) {
            return posix().dup(descriptor);
        }

    }

    @CoreMethod(names = "dup2", isModuleFunction = true, required = 2, lowerFixnum = { 1, 2 })
    public abstract static class Dup2Node extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int dup2(int oldFd, int newFd) {
            return posix().dup2(oldFd, newFd);
        }

    }

    @CoreMethod(names = "fchmod", isModuleFunction = true, required = 2, lowerFixnum = {1, 2})
    public abstract static class FchmodNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int fchmod(int one, int mode) {
            return posix().fchmod(one, mode);
        }

    }

    @CoreMethod(names = "fsync", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class FsyncNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int fsync(int descriptor) {
            return posix().fsync(descriptor);
        }

    }

    @CoreMethod(names = "fchown", isModuleFunction = true, required = 3, lowerFixnum = {1, 2, 3})
    public abstract static class FchownNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int fchown(int descriptor, int owner, int group) {
            return posix().fchown(descriptor, owner, group);
        }

    }

    @CoreMethod(names = "getegid", isModuleFunction = true)
    public abstract static class GetEGIDNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getEGID() {
            return posix().getegid();
        }

    }

    @CoreMethod(names = "getenv", isModuleFunction = true, required = 1)
    public abstract static class GetenvNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @TruffleBoundary
        @Specialization(guards = "isRubyString(name)")
        public DynamicObject getenv(DynamicObject name) {
            String result = posix().getenv(decodeUTF8(name));

            if (result == null) {
                return nil();
            }

            return makeStringNode.executeMake(result, UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }
    }

    @CoreMethod(names = "geteuid", isModuleFunction = true)
    public abstract static class GetEUIDNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getEUID() {
            return posix().geteuid();
        }

    }

    @CoreMethod(names = "getgid", isModuleFunction = true)
    public abstract static class GetGIDNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getGID() {
            return posix().getgid();
        }

    }

    @CoreMethod(names = "getgroups", isModuleFunction = true)
    public abstract static class GetGroupsNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public DynamicObject getGroups() {
            final long[] groups = getContext().getNativePlatform().getPosix().getgroups();
            return createArray(groups, groups.length);
        }

    }

    @CoreMethod(names = "getrlimit", isModuleFunction = true, required = 2, lowerFixnum = 1)
    public abstract static class GetRLimitNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary(throwsControlFlowException = true)
        @Specialization(guards = "isRubyPointer(pointer)")
        public int getrlimit(int resource, DynamicObject pointer) {
            final int result = posix().getrlimit(resource, Layouts.POINTER.getPointer(pointer));

            if (result == -1) {
                throw new RaiseException(coreExceptions().errnoError(posix().errno(), this));
            }

            return result;
        }

    }

    @CoreMethod(names = "getuid", isModuleFunction = true)
    public abstract static class GetUIDNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getUID() {
            return posix().getuid();
        }

    }

    @CoreMethod(names = "memset", isModuleFunction = true, required = 3, lowerFixnum = 2)
    public abstract static class MemsetNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(pointer)")
        public DynamicObject memset(DynamicObject pointer, int c, long length) {
            Layouts.POINTER.getPointer(pointer).writeBytes(0, length, (byte) c);
            return pointer;
        }

    }

    @CoreMethod(names = "mkfifo", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class MkfifoNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int mkfifo(DynamicObject path, int mode) {
            return posix().mkfifo(decodeUTF8(path), mode);
        }

    }

    @CoreMethod(names = "readlink", isModuleFunction = true, required = 3, lowerFixnum = 3)
    public abstract static class ReadlinkNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary(throwsControlFlowException = true)
        @Specialization(guards = {"isRubyString(path)", "isRubyPointer(pointer)"})
        public int readlink(DynamicObject path, DynamicObject pointer, int bufsize) {
            final int result = posix().readlink(decodeUTF8(path), Layouts.POINTER.getPointer(pointer), bufsize);

            if (result == -1) {
                throw new RaiseException(coreExceptions().errnoError(posix().errno(), this));
            }

            return result;
        }

    }

    @CoreMethod(names = "setenv", isModuleFunction = true, required = 3, lowerFixnum = 3)
    public abstract static class SetenvNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = { "isRubyString(name)", "isRubyString(value)" })
        public int setenv(DynamicObject name, DynamicObject value, int overwrite) {
            final String nameString = decodeUTF8(name);
            invalidateENV(nameString);
            return posix().setenv(nameString, decodeUTF8(value), overwrite);
        }

    }

    @CoreMethod(names = "lchmod", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class LchmodNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int lchmod(DynamicObject path, int mode) {
            return posix().lchmod(decodeUTF8(path), mode);
        }

    }

    @CoreMethod(names = "link", isModuleFunction = true, required = 2)
    public abstract static class LinkNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyString(path)", "isRubyString(other)"})
        public int link(DynamicObject path, DynamicObject other) {
            return posix().link(decodeUTF8(path), decodeUTF8(other));
        }

    }

    @CoreMethod(names = "unlink", isModuleFunction = true, required = 1)
    public abstract static class UnlinkNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int unlink(DynamicObject path) {
            return posix().unlink(decodeUTF8(path));
        }

    }

    @CoreMethod(names = "umask", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class UmaskNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int umask(int mask) {
            return posix().umask(mask);
        }

    }

    @CoreMethod(names = "unsetenv", isModuleFunction = true, required = 1)
    public abstract static class UnsetenvNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(name)")
        public int unsetenv(DynamicObject name) {
            final String nameString = decodeUTF8(name);
            invalidateENV(nameString);
            return posix().unsetenv(nameString);
        }

    }

    @CoreMethod(names = "utimes", isModuleFunction = true, required = 2)
    public abstract static class UtimesNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary(throwsControlFlowException = true)
        @Specialization(guards = {"isRubyString(path)", "isRubyPointer(pointer)"})
        public int utimes(DynamicObject path, DynamicObject pointer) {
            final int result = posix().utimes(decodeUTF8(path), Layouts.POINTER.getPointer(pointer));

            if (result == -1) {
                throw new RaiseException(coreExceptions().errnoError(posix().errno(), this));
            }

            return result;
        }

    }

    @CoreMethod(names = "mkdir", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class MkdirNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int mkdir(DynamicObject path, int mode) {
            return posix().mkdir(decodeUTF8(path), mode);
        }

    }

    @CoreMethod(names = "chdir", isModuleFunction = true, required = 1)
    public abstract static class ChdirNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int chdir(DynamicObject path) {
            final String pathString = decodeUTF8(path);
            return posix().chdir(pathString);
        }

    }

    @CoreMethod(names = "getpriority", isModuleFunction = true, required = 2, lowerFixnum = { 1, 2 })
    public abstract static class GetPriorityNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getpriority(int kind, int id) {
            return posix().getpriority(kind, id);
        }

    }

    @CoreMethod(names = "setgid", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class SetgidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setgid(int gid) {
            return posix().setgid(gid);
        }

    }

    @CoreMethod(names = "setpgid", isModuleFunction = true, required = 2, lowerFixnum = {1, 2})
    public abstract static class SetpgidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setpgid(int pid, int pgid) {
            return posix().setpgid(pid, pgid);
        }

    }

    @CoreMethod(names = "setpriority", isModuleFunction = true, required = 3, lowerFixnum = { 1, 2, 3 })
    public abstract static class SetPriorityNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setpriority(int kind, int id, int priority) {
            return posix().setpriority(kind, id, priority);
        }

    }

    @CoreMethod(names = "setresuid", isModuleFunction = true, required = 3, lowerFixnum = { 1, 2, 3 })
    public abstract static class SetResuidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setresuid(int uid, int id, int priority) {
            throw new RaiseException(coreExceptions().notImplementedError("setresuid", this));
        }

    }

    @CoreMethod(names = "seteuid", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class SetEuidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int seteuid(int uid) {
            return posix().seteuid(uid);
        }

    }

    @CoreMethod(names = "setreuid", isModuleFunction = true, required = 2, lowerFixnum = { 1, 2 })
    public abstract static class SetReuidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setreuid(int uid, int id) {
            throw new RaiseException(coreExceptions().notImplementedError("setreuid", this));
        }

    }

    @CoreMethod(names = "setrlimit", isModuleFunction = true, required = 2, lowerFixnum = 1)
    public abstract static class SetRLimitNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary(throwsControlFlowException = true)
        @Specialization(guards = "isRubyPointer(pointer)")
        public int setrlimit(int resource, DynamicObject pointer,
                @Cached("create()") BranchProfile errorProfile) {
            final int result = posix().setrlimit(resource, Layouts.POINTER.getPointer(pointer));

            if (result == -1) {
                errorProfile.enter();
                throw new RaiseException(coreExceptions().errnoError(posix().errno(), this));
            }

            return result;
        }

    }

    @CoreMethod(names = "setruid", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class SetRuidNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public int setruid(int uid) {
            throw new RaiseException(coreExceptions().notImplementedError("setruid", this));
        }

    }

    @CoreMethod(names = "setuid", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class SetUidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setuid(int uid) {
            return posix().setuid(uid);
        }

    }

    @CoreMethod(names = "setsid", isModuleFunction = true)
    public abstract static class SetSidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int setsid() {
            return posix().setsid();
        }

    }

    @CoreMethod(names = "flock", isModuleFunction = true, required = 2, lowerFixnum = { 1, 2 })
    public abstract static class FlockNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int flock(int fd, int constant) {
            return posix().flock(fd, constant);
        }

    }

    @CoreMethod(names = "major", isModuleFunction = true, required = 1)
    public abstract static class MajorNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public int major(long dev) {
            if (false) { // if (Platform.getPlatform().getOS() == OS.SOLARIS) {
                return (int) (dev >> 32); // Solaris has major number in the upper 32 bits.
            } else {
                return (int) ((dev >> 24) & 0xff);

            }
        }
    }

    @CoreMethod(names = "minor", isModuleFunction = true, required = 1)
    public abstract static class MinorNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        public int minor(long dev) {
            if (false) { // if (Platform.getPlatform().getOS() == OS.SOLARIS) {
                return (int) dev; // Solaris has minor number in the lower 32 bits.
            } else {
                return (int) (dev & 0xffffff);
            }
        }

    }

    @CoreMethod(names = "rename", isModuleFunction = true, required = 2)
    public abstract static class RenameNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyString(path)", "isRubyString(other)"})
        public int rename(DynamicObject path, DynamicObject other) {
            return posix().rename(decodeUTF8(path), decodeUTF8(other));
        }

    }

    @CoreMethod(names = "rmdir", isModuleFunction = true, required = 1)
    public abstract static class RmdirNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(path)")
        public int rmdir(DynamicObject path) {
            return posix().rmdir(decodeUTF8(path));
        }

    }

    @CoreMethod(names = "getcwd", isModuleFunction = true)
    public abstract static class GetcwdNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public DynamicObject getcwd() {
            final String path = posix().getcwd();

            // TODO (nirvdrum 12-Sept-16) The rope table always returns UTF-8, but this call should be based on Encoding.default_external and reflect updates to that value.
            return StringOperations.createString(getContext(), getContext().getRopeTable().getRope(path));
        }

    }

    @CoreMethod(names = "errno", isModuleFunction = true)
    public abstract static class ErrnoNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int errno() {
            return posix().errno();
        }

    }

    @CoreMethod(names = "errno=", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class ErrnoAssignNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int errno(int errno) {
            posix().errno(errno);
            return 0;
        }

    }

    @CoreMethod(names = "fcntl", isModuleFunction = true, required = 3, lowerFixnum = {1, 2, 3})
    public abstract static class FcntlNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isNil(nil)")
        public int fcntl(int fd, int fcntl, Object nil) {
            return posix().fcntl(fd, Fcntl.valueOf(fcntl));
        }

        @TruffleBoundary
        @Specialization
        public int fcntl(int fd, int fcntl, int arg) {
            return posix().fcntlInt(fd, Fcntl.valueOf(fcntl), arg);
        }

    }

    @CoreMethod(names = "getpgid", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class GetpgidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getpgid(int pid) {
            return posix().getpgid(pid);
        }

    }

    @CoreMethod(names = "getpgrp", isModuleFunction = true)
    public abstract static class GetpgrpNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getpgrp() {
            return posix().getpgrp();
        }

    }

    @CoreMethod(names = "isatty", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class IsATTYNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int isATTY(int fd) {
            return posix().isatty(fd);
        }

    }

    @CoreMethod(names = "getppid", isModuleFunction = true)
    public abstract static class GetppidNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getppid() {
            return posix().getppid();
        }

    }

    @CoreMethod(names = "send", isModuleFunction = true, required = 4, lowerFixnum = {1, 3, 4})
    public abstract static class SendNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(buffer)")
        public int send(int descriptor, DynamicObject buffer, int bytes, int flags) {
            return nativeSockets().send(descriptor, Layouts.POINTER.getPointer(buffer), bytes, flags);
        }

    }

    @CoreMethod(names = "symlink", isModuleFunction = true, required = 2)
    public abstract static class SymlinkNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyString(first)", "isRubyString(second)"})
        public int symlink(DynamicObject first, DynamicObject second) {
            return posix().symlink(decodeUTF8(first), decodeUTF8(second));
        }

    }

    @CoreMethod(names = "_getaddrinfo", isModuleFunction = true, required = 4)
    public abstract static class GetAddrInfoNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isNil(hostName)", "isRubyString(serviceName)"})
        public int getaddrinfoNil(DynamicObject hostName, DynamicObject serviceName, DynamicObject hintsPointer, DynamicObject resultsPointer) {
            return getaddrinfoString(coreStrings().BIND_ALL_IPV4_ADDR.createInstance(), serviceName, hintsPointer, resultsPointer);
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubyString(hostName)", "isRubyString(serviceName)", "isRubyPointer(hintsPointer)", "isRubyPointer(resultsPointer)"})
        public int getaddrinfoString(DynamicObject hostName, DynamicObject serviceName, DynamicObject hintsPointer, DynamicObject resultsPointer) {
            return nativeSockets().getaddrinfo(
                    decodeUTF8(hostName),
                    decodeUTF8(serviceName),
                    Layouts.POINTER.getPointer(hintsPointer),
                    Layouts.POINTER.getPointer(resultsPointer));
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubyString(hostName)", "isNil(serviceName)", "isRubyPointer(hintsPointer)", "isRubyPointer(resultsPointer)"})
        public int getaddrinfo(DynamicObject hostName, DynamicObject serviceName, DynamicObject hintsPointer, DynamicObject resultsPointer) {
            return nativeSockets().getaddrinfo(
                    decodeUTF8(hostName),
                    null,
                    Layouts.POINTER.getPointer(hintsPointer),
                    Layouts.POINTER.getPointer(resultsPointer));
        }

    }

    @CoreMethod(names = "gethostbyname", isModuleFunction = true, required = 1)
    public abstract static class GetHostByNameNode extends CoreMethodArrayArgumentsNode {

        @Child private AllocateObjectNode allocateObjectNode = AllocateObjectNode.create();

        @Specialization(guards = "isRubyString(name)")
        public DynamicObject gethostbyname(DynamicObject name) {
            return allocateObjectNode.allocate(getContext().getCoreLibrary().getRubiniusFFIPointerClass(), new Pointer(getHostByName(name).address()));
        }

        @TruffleBoundary
        private jnr.ffi.Pointer getHostByName(DynamicObject name) {
            return nativeSockets().gethostbyname(StringOperations.getString(name));
        }

    }

    @CoreMethod(names = "sendto", isModuleFunction = true, required = 6, lowerFixnum = { 1, 3, 4, 6 })
    public abstract static class SendToNode extends CoreMethodArrayArgumentsNode {

        @Child private AllocateObjectNode allocateObjectNode = AllocateObjectNode.create();

        @Specialization(guards = "!isRubyPointer(dest_addr)")
        public int sendToStruct(VirtualFrame frame, int socket, DynamicObject message, int length, int flags, DynamicObject dest_addr, int dest_len,
                @Cached("new()") SnippetNode snippetNode) {
            final DynamicObject dest_addr_ptr = (DynamicObject) snippetNode.execute(frame, "dest_addr.to_ptr", "dest_addr", dest_addr);
            return sendTo(socket, message, length, flags, dest_addr_ptr, dest_len);
        }

        @Specialization(guards = "isRubyPointer(dest_addr)")
        public int sendTo(int socket, DynamicObject message, int length, int flags, DynamicObject dest_addr, int dest_len) {
            final Pointer messagePointer = Layouts.POINTER.getPointer(message);
            final Pointer destAddrPointer = Layouts.POINTER.getPointer(dest_addr);
            return sendToPrivate(socket, length, flags, dest_len, messagePointer, destAddrPointer);
        }

        @TruffleBoundary
        private int sendToPrivate(int socket, int length, int flags, int dest_len, Pointer messagePointer, Pointer destAddrPointer) {
            return nativeSockets().sendto(socket, messagePointer, length, flags, destAddrPointer, dest_len);
        }

    }

    @CoreMethod(names = "_connect", isModuleFunction = true, required = 3, lowerFixnum = { 1, 3 })
    public abstract static class ConnectNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(address)")
        public int connect(int socket, DynamicObject address, int address_len) {
            return nativeSockets().connect(socket, Layouts.POINTER.getPointer(address), address_len);
        }

    }

    @CoreMethod(names = "inet_network", isModuleFunction = true, required = 1)
    public abstract static class InetNetworkNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int inetNetwork(DynamicObject string) {
            return nativeSockets().inet_network(StringOperations.getString(string));
        }

    }

    @CoreMethod(names = "inet_pton", isModuleFunction = true, required = 3, lowerFixnum = 1)
    public abstract static class InetPtonNode extends CoreMethodArrayArgumentsNode {
        
        @TruffleBoundary
        @Specialization
        public int inetNetwork(int af, DynamicObject str, DynamicObject dst) {
            return nativeSockets().inet_pton(af, StringOperations.getString(str), Layouts.POINTER.getPointer(dst));
        }

    }

    @CoreMethod(names = "_socketpair", isModuleFunction = true, required = 4, lowerFixnum = { 1, 2, 3 })
    public abstract static class SocketpairNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int socketpair(int domain, int type, int protocolint, DynamicObject pointer) {
            return nativeSockets().socketpair(domain, type, protocolint, Layouts.POINTER.getPointer(pointer));
        }

    }

    @CoreMethod(names = "accept", isModuleFunction = true, required = 3, lowerFixnum = { 1, 3 })
    public abstract static class AcceptNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(addressPtr)")
        public int accept(int fd, DynamicObject addressPtr, DynamicObject socklenPointer) {
            final Pointer sockPointer = Layouts.POINTER.getPointer(socklenPointer);
            final Pointer addressPointer = Layouts.POINTER.getPointer(addressPtr);

            final int newFd = getContext().getThreadManager().runBlockingSystemCallUntilResult(this,
                    () -> nativeSockets().accept(fd, addressPointer, sockPointer));
            return ensureSuccessful(newFd);
        }

        protected int ensureSuccessful(int result, int errno, String extra) {
            assert result >= -1;
            if (result == -1) {
                throw new RaiseException(coreExceptions().errnoError(errno, extra, this));
            }
            return result;
        }

        protected int ensureSuccessful(int result) {
            return ensureSuccessful(result, posix().errno(), "");
        }

    }

    @CoreMethod(names = "freeaddrinfo", isModuleFunction = true, required = 1)
    public abstract static class FreeAddrInfoNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(addrInfo)")
        public DynamicObject freeaddrinfo(DynamicObject addrInfo) {
            nativeSockets().freeaddrinfo(Layouts.POINTER.getPointer(addrInfo));
            return nil();
        }

    }

    @CoreMethod(names = "gai_strerror", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class GaiStrErrorNode extends CoreMethodArrayArgumentsNode {

        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        public DynamicObject gai_strerror(int ecode) {
            final String errorMessage = nativeSockets().gai_strerror(ecode);
            return makeStringNode.executeMake(errorMessage, ASCIIEncoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @CoreMethod(names = "_getnameinfo", isModuleFunction = true, required = 7, lowerFixnum = {2, 4, 6, 7})
    public abstract static class GetNameInfoNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyPointer(sa)", "isRubyPointer(host)", "isRubyPointer(serv)"})
        public int getnameinfo(DynamicObject sa, int salen, DynamicObject host, int hostlen, DynamicObject serv, int servlen, int flags) {
            assert hostlen > 0;
            assert servlen > 0;

            return nativeSockets().getnameinfo(
                    Layouts.POINTER.getPointer(sa),
                    salen,
                    Layouts.POINTER.getPointer(host),
                    hostlen,
                    Layouts.POINTER.getPointer(serv),
                    servlen,
                    flags);
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubyPointer(sa)", "isNil(host)", "isRubyPointer(serv)"})
        public int getnameinfoNullHost(DynamicObject sa, int salen, DynamicObject host, int hostlen, DynamicObject serv, int servlen, int flags) {
            assert hostlen == 0;
            assert servlen > 0;

            return nativeSockets().getnameinfo(
                    Layouts.POINTER.getPointer(sa),
                    salen,
                    Pointer.JNR_NULL,
                    hostlen,
                    Layouts.POINTER.getPointer(serv),
                    servlen,
                    flags);
        }

        @TruffleBoundary
        @Specialization(guards = {"isRubyPointer(sa)", "isRubyPointer(host)", "isNil(serv)"})
        public int getnameinfoNullService(DynamicObject sa, int salen, DynamicObject host, int hostlen, DynamicObject serv, int servlen, int flags) {
            assert hostlen > 0;
            assert servlen == 0;

            return nativeSockets().getnameinfo(
                    Layouts.POINTER.getPointer(sa),
                    salen,
                    Layouts.POINTER.getPointer(host),
                    hostlen,
                    Pointer.JNR_NULL,
                    servlen,
                    flags);
        }

    }

    @CoreMethod(names = "shutdown", isModuleFunction = true, required = 2, lowerFixnum = {1, 2})
    public abstract static class ShutdownNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int shutdown(int socket, int how) {
            return nativeSockets().shutdown(socket, how);
        }

    }

    @CoreMethod(names = "socket", isModuleFunction = true, required = 3, lowerFixnum = {1, 2, 3})
    public abstract static class SocketNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int getnameinfo(int domain, int type, int protocol) {
            return nativeSockets().socket(domain, type, protocol);
        }

    }

    @CoreMethod(names = "setsockopt", isModuleFunction = true, required = 5, lowerFixnum = { 1, 2, 3, 5 })
    public abstract static class SetSockOptNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(optionValue)")
        public int setsockopt(int socket, int level, int optionName, DynamicObject optionValue, int optionLength) {
            return nativeSockets().setsockopt(socket, level, optionName, Layouts.POINTER.getPointer(optionValue), optionLength);
        }

    }

    @CoreMethod(names = "_bind", isModuleFunction = true, required = 3, lowerFixnum = {1, 3})
    public abstract static class BindNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(address)")
        public int bind(int socket, DynamicObject address, int addressLength) {
            return nativeSockets().bind(socket, Layouts.POINTER.getPointer(address), addressLength);
        }

    }

    @CoreMethod(names = "listen", isModuleFunction = true, required = 2, lowerFixnum = {1, 2})
    public abstract static class ListenNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int listen(int socket, int backlog) {
            return nativeSockets().listen(socket, backlog);
        }

    }

    @CoreMethod(names = "gethostname", isModuleFunction = true, required = 2, lowerFixnum = 2)
    public abstract static class GetHostNameNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyPointer(name)")
        public int getHostName(DynamicObject name, int nameLength) {
            return nativeSockets().gethostname(Layouts.POINTER.getPointer(name), nameLength);
        }

    }

    @CoreMethod(names = "_getpeername", isModuleFunction = true, required = 3, lowerFixnum = 1)
    public abstract static class GetPeerNameNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyPointer(address)", "isRubyPointer(addressLength)"})
        public int getPeerName(int socket, DynamicObject address, DynamicObject addressLength) {
            return nativeSockets().getpeername(socket, Layouts.POINTER.getPointer(address), Layouts.POINTER.getPointer(addressLength));
        }

    }

    @CoreMethod(names = "_getsockname", isModuleFunction = true, required = 3, lowerFixnum = 1)
    public abstract static class GetSockNameNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = {"isRubyPointer(address)", "isRubyPointer(addressLength)"})
        public int getSockName(int socket, DynamicObject address, DynamicObject addressLength) {
            return nativeSockets().getsockname(socket, Layouts.POINTER.getPointer(address), Layouts.POINTER.getPointer(addressLength));
        }

    }

    @CoreMethod(names = "_getsockopt", isModuleFunction = true, required = 5, lowerFixnum = {1, 2, 3})
    public abstract static class GetSockOptNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = { "isRubyPointer(optval)", "isRubyPointer(optlen)" })
        public int getSockOptions(int sockfd, int level, int optname, DynamicObject optval, DynamicObject optlen) {
            return nativeSockets().getsockopt(sockfd, level, optname, Layouts.POINTER.getPointer(optval), Layouts.POINTER.getPointer(optlen));
        }

        // This should probably done at a higher-level, but rubysl/socket does not handle it.
        @Specialization(guards = { "isRubySymbol(level)", "isRubySymbol(optname)", "isRubyPointer(optval)", "isRubyPointer(optlen)" })
        public int getSockOptionsSymbols(
                VirtualFrame frame,
                int sockfd,
                DynamicObject level,
                DynamicObject optname,
                DynamicObject optval,
                DynamicObject optlen,
                @Cached("new()") SnippetNode snippetNode) {
            int levelInt = (int) snippetNode.execute(frame, "Socket.const_get('SOL_' + name)", "name", Layouts.SYMBOL.getString(level));
            int optnameInt = (int) snippetNode.execute(frame, "Socket.const_get('SOL_' + name)", "name", Layouts.SYMBOL.getString(optname));
            return getSockOptions(sockfd, levelInt, optnameInt, optval, optlen);
        }

    }

    @CoreMethod(names = "close", isModuleFunction = true, required = 1, lowerFixnum = 1)
    public abstract static class CloseNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        public int close(int file) {
            return posix().close(file);
        }

    }

    @CoreMethod(names = "kill", isModuleFunction = true, required = 3, lowerFixnum = { 1, 2 })
    public abstract static class KillNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "isRubyString(signalName)")
        public int kill(int pid, int signalNumber, DynamicObject signalName) {
            int self = posix().getpid();

            if (self == pid) {
                Signal signal = getContext().getNativePlatform().getSignalManager().createSignal(decodeUTF8(signalName));
                return raise(signal);
            } else {
                return posix().kill(pid, signalNumber);
            }
        }

        @TruffleBoundary(throwsControlFlowException = true)
        private int raise(Signal signal) {
            try {
                getContext().getNativePlatform().getSignalManager().raise(signal);
            } catch (IllegalArgumentException e) {
                throw new RaiseException(coreExceptions().argumentError(e.getMessage(), this));
            }
            return 1;
        }

    }

}
