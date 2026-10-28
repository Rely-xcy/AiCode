package main

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// 符号链接最多展开这么多次。Linux 的 40 是经验值，真正的目的是防环（a→b→a）。
const maxSymlinkHops = 40

// sandbox 把 -root 固化成一个绝对、已去符号链接的边界。
// 之后每次请求都把路径换算成真实路径再交给 os 包，os 那一层就不会再越过边界。
type sandbox struct {
	root string
	// alias 是启动时传入的 -root 原文（绝对化后）。root 本身是符号链接时，
	// 客户端通常还用那个别名拼路径；只按 root 做词法前缀判断会把整批请求误判成越界。
	alias string
	home  string
}

func newSandbox(root string) (*sandbox, error) {
	if root == "" {
		return nil, errors.New("-root is required")
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	// root 本身可能是符号链接（例如 /home/u/workspace → /data/workspace），
	// 先解析掉，后面拿它做前缀比较才有意义。
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(real)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, errors.New("root is not a directory: " + real)
	}
	sb := &sandbox{root: real, home: homeDir()}
	if abs != real {
		sb.alias = abs
	}
	return sb, nil
}

func homeDir() string {
	if h, err := os.UserHomeDir(); err == nil && h != "" {
		return h
	}
	return os.Getenv("HOME")
}

// contains 用「分隔符边界」判断包含关系，而不是裸 HasPrefix：
// 裸前缀会让 /root/workspace-evil 被当成 /root/workspace 的子路径。
func (s *sandbox) contains(p string) bool {
	return pathWithin(s.root, p)
}

// containsAlias 与 contains 同义，只是比对启动时传入的那个路径写法。
func (s *sandbox) containsAlias(p string) bool {
	return s.alias != "" && pathWithin(s.alias, p)
}

func pathWithin(base, p string) bool {
	if p == base {
		return true
	}
	if base == string(os.PathSeparator) {
		return strings.HasPrefix(p, base)
	}
	return strings.HasPrefix(p, base+string(os.PathSeparator))
}

// expandHome 展开 `~` 与 `~/...`。协议里 path 允许带 home 前缀，
// 但展开后仍要走同一套边界检查：home 本身可能在 root 之外。
func (s *sandbox) expandHome(p string) string {
	if s.home == "" {
		return p
	}
	if p == "~" {
		return s.home
	}
	if strings.HasPrefix(p, "~/") {
		return filepath.Join(s.home, p[2:])
	}
	return p
}

// resolve 把请求里的 path 换算成可以安全交给 os 包的真实路径。
//
// 顺序是刻意的：先做纯词法检查（挡住 ../ 与根外绝对路径，代价小、错误信息准），
// 再逐段解析符号链接（挡住 link → /etc 这类越界），最后再确认一次结果仍在 root 内。
// 最后一步是唯一有权威的判定：前面的词法检查只是提前拒绝，不承担安全职责。
func (s *sandbox) resolve(p string) (string, error) {
	if p == "" {
		return "", newErr(errInvalid, "path is required")
	}
	expanded := s.expandHome(p)
	if !filepath.IsAbs(expanded) {
		return "", newErr(errInvalid, "path must be absolute")
	}
	clean := filepath.Clean(expanded)
	if !s.contains(clean) && !s.containsAlias(clean) {
		return "", newErr(errInvalid, "path escapes root")
	}
	real, err := s.evalPath(clean, 0)
	if err != nil {
		return "", err
	}
	if !s.contains(real) {
		return "", newErr(errInvalid, "symlink target escapes root")
	}
	return real, nil
}

// evalPath 从文件系统根开始逐段推进，遇到符号链接就地展开并重新开始。
//
// 不用 filepath.EvalSymlinks 是因为它对「最后一段不存在」无能为力（写新文件、
// 建目录都得先拼路径），而退化成「解析存在的最长前缀」又会漏掉悬空链接：
// 一个指向 root 外的悬空链接，前缀解析会把链接自身当作路径返回，随后 O_CREATE
// 会顺着它把文件创建到 root 外面。逐段解析能同时满足这两点。
//
// 从 "/" 而不是从 root 开始走，是为了支持「-root 本身是符号链接」的写法：
// 客户端拿启动参数拼出来的路径会带上那层别名，从别名往下走不出 root 的真实位置。
func (s *sandbox) evalPath(clean string, hops int) (string, error) {
	if hops > maxSymlinkHops {
		return "", newErr(errInvalid, "too many levels of symbolic links")
	}
	parts := splitComponents(clean)
	cur := string(os.PathSeparator)
	for i, part := range parts {
		next := filepath.Join(cur, part)
		info, err := os.Lstat(next)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				// 从这里开始都不存在，也就不可能再有符号链接，直接拼回去。
				return joinAll(next, parts[i+1:]), nil
			}
			return "", asProtoErr(err)
		}
		if info.Mode()&os.ModeSymlink != 0 {
			target, err := os.Readlink(next)
			if err != nil {
				return "", asProtoErr(err)
			}
			dest := target
			if !filepath.IsAbs(dest) {
				dest = filepath.Join(cur, dest)
			}
			dest = filepath.Clean(dest)
			return s.evalPath(joinAll(dest, parts[i+1:]), hops+1)
		}
		cur = next
	}
	return cur, nil
}

func splitComponents(rel string) []string {
	raw := strings.Split(rel, string(os.PathSeparator))
	out := make([]string, 0, len(raw))
	for _, p := range raw {
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func joinAll(base string, rest []string) string {
	for _, r := range rest {
		base = filepath.Join(base, r)
	}
	return base
}

// asProtoErr 把 os 的 errno 归类到协议错误码。msg 用 errno 的英文原文，
// 协议要求 msg 是英文且不超过 120 字符，errno 文案天然满足。
func asProtoErr(err error) *protoError {
	var pe *protoError
	if errors.As(err, &pe) {
		return pe
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return &protoError{code: errnoCode(errno), msg: errno.Error()}
	}
	if errors.Is(err, fs.ErrNotExist) {
		return newErr(errNotFound, "no such file or directory")
	}
	return newErr(errIO, err.Error())
}

func errnoCode(errno syscall.Errno) string {
	switch errno {
	case syscall.ENOENT:
		return errNotFound
	case syscall.EACCES, syscall.EPERM:
		return errAccess
	case syscall.EISDIR:
		return errIsDir
	case syscall.ENOTDIR:
		return errNotDir
	case syscall.EEXIST, syscall.ENOTEMPTY:
		// 协议没有 ENOTEMPTY：非空目录删不掉时，最贴近的语义是「目标还在」。
		return errExist
	case syscall.EINVAL, syscall.ELOOP, syscall.ENAMETOOLONG:
		return errInvalid
	case syscall.EFBIG:
		return errTooBig
	default:
		return errIO
	}
}
