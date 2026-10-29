package main

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type agent struct {
	sb    *sandbox
	log   *logger
	hasRg bool
}

// reply 是一帧待发送的响应：头 + 裸 body。
type reply struct {
	resp *response
	body []byte
}

// handle 是 op 分发表。单线程：一个 goroutine 读、执行、写，靠 Android 端开多个
// 进程实例来并发，agent 内部不引入锁与 worker pool。
func (a *agent) handle(req *request, body []byte) *reply {
	if req.Op == "" {
		return failed(req.ID, newErr(errInvalid, "op is required"))
	}
	var (
		resp *response
		out  []byte
		perr *protoError
	)
	switch req.Op {
	case "hello":
		resp, out, perr = a.opHello(req)
	case "stat":
		resp, out, perr = a.opStat(req)
	case "list":
		resp, out, perr = a.opList(req)
	case "read":
		resp, out, perr = a.opRead(req)
	case "write":
		resp, out, perr = a.opWrite(req, body)
	case "mkdir":
		resp, out, perr = a.opMkdir(req)
	case "remove":
		resp, out, perr = a.opRemove(req)
	case "rename":
		resp, out, perr = a.opRename(req)
	case "chmod":
		resp, out, perr = a.opChmod(req)
	case "copy":
		resp, out, perr = a.opCopy(req)
	case "hash":
		resp, out, perr = a.opHash(req)
	case "grep":
		resp, out, perr = a.opGrep(req)
	default:
		perr = newErr(errUnsupported, "unsupported op: "+req.Op)
	}
	if perr != nil {
		return failed(req.ID, perr)
	}
	// 超过 64 MiB 的 body 客户端读不了；截断发出去会被当成本帧完整内容，比失败更糟。
	if len(out) > maxBodyBytes {
		return failed(req.ID, newErr(errTooBig, "response body exceeds 64 MiB"))
	}
	return &reply{resp: resp, body: out}
}

func failed(id int, pe *protoError) *reply {
	return &reply{resp: &response{ID: id, Ok: false, Err: pe.code, Msg: clampMsg(pe.msg)}}
}

func okResponse(id int) *response { return &response{ID: id, Ok: true} }

func (a *agent) opHello(req *request) (*response, []byte, *protoError) {
	resp := okResponse(req.ID)
	goVersion := strings.TrimPrefix(runtime.Version(), "go")
	hasRg := a.hasRg
	canHash := true
	resp.Proto = intPtr(protoVersion)
	resp.Go = goVersion
	resp.OS = runtime.GOOS
	resp.Arch = runtime.GOARCH
	resp.HasRg = &hasRg
	resp.Home = a.sb.home
	resp.CanHash = &canHash
	return resp, nil, nil
}

func (a *agent) opStat(req *request) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	// Stat 跟随符号链接：客户端看到的应该是目标的信息，这与 read/list 的语义一致。
	info, err := os.Stat(p)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	resp := okResponse(req.ID)
	resp.Size = int64Ptr(info.Size())
	resp.Mtime = int64Ptr(info.ModTime().Unix())
	resp.IsDir = boolPtr(info.IsDir())
	resp.Perm = permString(info.Mode())
	return resp, nil, nil
}

func (a *agent) opList(req *request) (*response, []byte, *protoError) {
	// depth 用 0 表示不限层数：recursive 不带 maxDepth 时整棵树都列，
	// 而 maxDepth=0 在实测语义里也是「不限」，不是「只看一层」。
	depth := defaultMaxDepth
	if req.Recursive {
		depth = 0
	}
	if req.MaxDepth != nil {
		if *req.MaxDepth < 0 {
			return nil, nil, newErr(errInvalid, "maxDepth must not be negative")
		}
		depth = *req.MaxDepth
	}
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	info, err := os.Stat(p)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	if !info.IsDir() {
		return nil, nil, newErr(errNotDir, "not a directory")
	}
	entries := make([]listEntry, 0, 64)
	if perr := collectEntries(p, depth, &entries); perr != nil {
		return nil, nil, perr
	}
	out, err := encodeBody(entries)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), out, nil
}

// collectEntries 深度优先、按名字升序收集（os.ReadDir 本身有序）。
// 只输出名字不带相对路径：body 体积与嵌套深度无关，客户端按需再 list 下一层。
func collectEntries(dir string, depth int, out *[]listEntry) *protoError {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return asProtoErr(err)
	}
	for _, e := range entries {
		entry := listEntry{Name: e.Name(), Type: typeCode(e.Type())}
		if info, err := e.Info(); err == nil {
			entry.Mtime = info.ModTime().Unix()
			// 目录与符号链接的大小对客户端无意义（目录大小随文件系统变），
			// 统一报 0，避免客户端拿它算进度或做比较。
			if entry.Type == 0 {
				entry.Size = info.Size()
			}
		}
		*out = append(*out, entry)
		if entry.Type == 1 && depth != 1 {
			next := depth
			if next > 0 {
				next--
			}
			if perr := collectEntries(filepath.Join(dir, e.Name()), next, out); perr != nil {
				return perr
			}
		}
	}
	return nil
}

func typeCode(m os.FileMode) int {
	switch {
	case m&os.ModeSymlink != 0:
		return 2
	case m.IsDir():
		return 1
	case m.IsRegular():
		return 0
	default:
		return 3
	}
}

// permString 输出 rw-r--r-- 形态。Android 侧 FileEntry.permissions 直接展示这个字符串，
// 不复用 os.FileMode.String() 是因为它会带 d/l 前缀并按需补 s/t 位，形态不稳定。
func permString(m os.FileMode) string {
	const rwx = "rwxrwxrwx"
	perm := m.Perm()
	b := []byte("---------")
	for i := 0; i < len(b); i++ {
		if perm&(1<<uint(8-i)) != 0 {
			b[i] = rwx[i]
		}
	}
	return string(b)
}

func (a *agent) opRead(req *request) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	length := defaultReadLen
	if req.Len != nil {
		if *req.Len < 0 {
			return nil, nil, newErr(errInvalid, "len must not be negative")
		}
		if *req.Len > maxReadLen {
			return nil, nil, newErr(errTooBig, "len exceeds 1 MiB")
		}
		length = *req.Len
	}
	offset := int64(0)
	if req.Offset != nil {
		if *req.Offset < 0 {
			return nil, nil, newErr(errInvalid, "offset must not be negative")
		}
		offset = *req.Offset
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	// 目录也能 Open 成功，必须在读之前挡掉，否则会拿到 EISDIR 且错误信息指向 read 而非 open。
	if info.IsDir() {
		return nil, nil, newErr(errIsDir, "is a directory")
	}
	size := info.Size()
	resp := okResponse(req.ID)
	resp.Size = &size
	// offset 越过末尾是合法的「读到空」，不报错：客户端按固定块大小续读，
	// 最后一块之后还会再来一次，报错会让它以为文件坏了。
	if length == 0 || offset >= size {
		return resp, nil, nil
	}
	want := int64(length)
	if remain := size - offset; want > remain {
		want = remain
	}
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return nil, nil, asProtoErr(err)
	}
	buf := make([]byte, want)
	n, err := io.ReadFull(f, buf)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) {
		return nil, nil, asProtoErr(err)
	}
	return resp, buf[:n], nil
}

func (a *agent) opWrite(req *request, body []byte) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	mode := defaultFileMode
	if req.Mode != nil {
		if *req.Mode < 0 {
			return nil, nil, newErr(errInvalid, "mode must not be negative")
		}
		mode = *req.Mode & 0o777
	}
	overwrite := true
	if req.Overwrite != nil {
		overwrite = *req.Overwrite
	}
	// 目标是目录：在动临时文件之前就拒，否则 rename 盖目录会得到 EEXIST，
	// 与「你把文件写到目录上了」的真实原因对不上。
	if info, statErr := os.Stat(p); statErr == nil && info.IsDir() {
		return nil, nil, newErr(errIsDir, "is a directory")
	}

	// 已存在且不允许覆盖：提前返回，别等到 rename 才发现
	if !overwrite {
		if _, statErr := os.Lstat(p); statErr == nil {
			return nil, nil, newErr(errExist, "file already exists")
		}
	}

	// 同目录临时文件 + rename：中途失败或断线时目标文件保持原样，
	// 读方看到的要么是旧内容、要么是完整新内容。直接 open 目标会留下半截文件，
	// 而远端跑的是模型，它可能立刻就去读自己刚写的那个文件。
	tmp := fmt.Sprintf("%s.tmp.%d.%d", p, os.Getpid(), time.Now().UnixNano())
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, os.FileMode(mode))
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	_, writeErr := f.Write(body)
	closeErr := f.Close()
	if writeErr != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(writeErr)
	}
	if closeErr != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(closeErr)
	}
	if err := os.Rename(tmp, p); err != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(err)
	}
	size := int64(len(body))
	resp := okResponse(req.ID)
	resp.Size = &size
	return resp, nil, nil
}

func (a *agent) opMkdir(req *request) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	// 已存在且是目录算成功（与 mkdir -p 一致），已存在且是文件才报 EEXIST：
	// MkdirAll 对后者返回的是 ENOTDIR，与「目标已存在」的语义不符。
	info, statErr := os.Stat(p)
	switch {
	case statErr == nil && info.IsDir():
		return okResponse(req.ID), nil, nil
	case statErr == nil:
		return nil, nil, newErr(errExist, "file already exists")
	case !errors.Is(statErr, fs.ErrNotExist):
		return nil, nil, asProtoErr(statErr)
	}
	if err := os.MkdirAll(p, defaultDirMode); err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), nil, nil
}

func (a *agent) opRemove(req *request) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	// 根目录永不删：Remove 一个空 root 会成功，之后所有请求都落在不存在的目录上，
	// 远端会变成一个「活着但什么都做不了」的守护进程。
	if p == a.sb.root {
		return nil, nil, newErr(errInvalid, "refusing to remove root")
	}
	if _, err := os.Lstat(p); err != nil {
		// RemoveAll 对不存在的路径返回 nil，这里显式报 ENOENT，
		// 与非递归删除保持同一套语义，客户端不用为两种情况写不同分支。
		return nil, nil, asProtoErr(err)
	}
	if req.Recursive {
		err = os.RemoveAll(p)
	} else {
		err = os.Remove(p)
	}
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), nil, nil
}

func (a *agent) opRename(req *request) (*response, []byte, *protoError) {
	if req.To == "" {
		return nil, nil, newErr(errInvalid, "to is required")
	}
	from, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	to, err := a.sb.resolve(req.To)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	if err := os.Rename(from, to); err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), nil, nil
}

func (a *agent) opChmod(req *request) (*response, []byte, *protoError) {
	if req.Mode == nil {
		return nil, nil, newErr(errInvalid, "mode is required")
	}
	if *req.Mode < 0 {
		return nil, nil, newErr(errInvalid, "mode must not be negative")
	}
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	// 只取权限位：调用方可能传了 setuid/sticky 这些高位，代理不该替用户改它们。
	if err := os.Chmod(p, os.FileMode(*req.Mode)&0o777); err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), nil, nil
}

// opCopy 现在只复制单个普通文件：目录递归复制走不到上传内置资源那几条路径，
// 而递归复制要自己处理权限/符号链接/失败回滚，不值得为它引入一个半残实现。
func (a *agent) opCopy(req *request) (*response, []byte, *protoError) {
	if req.To == "" {
		return nil, nil, newErr(errInvalid, "to is required")
	}
	from, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	to, err := a.sb.resolve(req.To)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	src, err := os.Open(from)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	defer src.Close()
	info, err := src.Stat()
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	if info.IsDir() {
		return nil, nil, newErr(errIsDir, "copy of directories is not supported")
	}
	if req.Overwrite != nil && !*req.Overwrite {
		if _, statErr := os.Lstat(to); statErr == nil {
			return nil, nil, newErr(errExist, "file already exists")
		}
	}
	// 与 write 一样先写临时文件再 rename：复制中断时目标不会变成半截。
	tmp := fmt.Sprintf("%s.tmp.%d.%d", to, os.Getpid(), time.Now().UnixNano())
	dst, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, info.Mode().Perm())
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	_, copyErr := io.Copy(dst, src)
	closeErr := dst.Close()
	if copyErr != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(copyErr)
	}
	if closeErr != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(closeErr)
	}
	if err := os.Rename(tmp, to); err != nil {
		os.Remove(tmp)
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), nil, nil
}

func (a *agent) opHash(req *request) (*response, []byte, *protoError) {
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	if info.IsDir() {
		return nil, nil, newErr(errIsDir, "is a directory")
	}
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return nil, nil, asProtoErr(err)
	}
	resp := okResponse(req.ID)
	resp.Size = int64Ptr(info.Size())
	resp.Hash = "sha256:" + hex.EncodeToString(h.Sum(nil))
	return resp, nil, nil
}
