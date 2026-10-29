package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

func listNames(t *testing.T, body []byte) []string {
	t.Helper()
	var entries []listEntry
	if err := json.Unmarshal(body, &entries); err != nil {
		t.Fatalf("list body 不是合法 JSON 数组: %v (%s)", err, body)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name)
	}
	return names
}

func TestHello(t *testing.T) {
	_, root := newTestRoot(t)
	resp, body, _ := call(t, root, map[string]any{"id": 1, "op": "hello"}, nil)
	if !resp.Ok || len(body) != 0 {
		t.Fatalf("hello 失败: %+v", resp)
	}
	if resp.Proto == nil || *resp.Proto != protoVersion {
		t.Fatalf("proto = %v", resp.Proto)
	}
	if resp.OS == "" || resp.Arch == "" || resp.Go == "" {
		t.Fatalf("能力字段缺失: %+v", resp)
	}
	if resp.Home == "" || resp.HasRg == nil || resp.CanHash == nil || !*resp.CanHash {
		t.Fatalf("能力字段缺失: %+v", resp)
	}
}

func TestStat(t *testing.T) {
	_, root := newTestRoot(t)
	file := filepath.Join(root, "a.txt")
	resp, body, _ := call(t, root, map[string]any{"id": 1, "op": "stat", "path": file}, nil)
	if !resp.Ok || len(body) != 0 {
		t.Fatalf("stat 失败: %+v", resp)
	}
	if resp.Size == nil || *resp.Size != int64(len("hello world\n")) {
		t.Fatalf("size = %v", resp.Size)
	}
	if resp.IsDir == nil || *resp.IsDir {
		t.Fatalf("isDir = %v", resp.IsDir)
	}
	if resp.Mtime == nil || *resp.Mtime == 0 {
		t.Fatalf("mtime = %v", resp.Mtime)
	}
	if resp.Perm != "rw-------" {
		t.Fatalf("perm = %q，期望 rw-------", resp.Perm)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 2, "op": "stat", "path": root}, nil)
	if resp.IsDir == nil || !*resp.IsDir || resp.Perm != "rwx------" {
		t.Fatalf("目录 stat 不对: %+v", resp)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 3, "op": "stat", "path": filepath.Join(root, "nope")}, nil)
	if resp.Ok || resp.Err != errNotFound {
		t.Fatalf("缺失路径应报 ENOENT: %+v", resp)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 4, "op": "stat", "path": "/etc/passwd"}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("根外路径应报 EINVAL: %+v", resp)
	}
}

func TestRead(t *testing.T) {
	_, root := newTestRoot(t)
	file := filepath.Join(root, "a.txt")

	resp, body, _ := call(t, root, map[string]any{"id": 1, "op": "read", "path": file, "offset": 6, "len": 5}, nil)
	if !resp.Ok || string(body) != "world" || resp.Size == nil || *resp.Size != 12 {
		t.Fatalf("read 片段不对: %+v body=%q", resp, body)
	}

	// len 缺省是 65536，0 才是「读 0 字节」
	resp, body, _ = call(t, root, map[string]any{"id": 2, "op": "read", "path": file}, nil)
	if string(body) != "hello world\n" {
		t.Fatalf("默认 len 应读全文: %q", body)
	}
	resp, body, _ = call(t, root, map[string]any{"id": 3, "op": "read", "path": file, "len": 0}, nil)
	if !resp.Ok || len(body) != 0 || *resp.Size != 12 {
		t.Fatalf("len=0 应返回空 body: %+v body=%q", resp, body)
	}

	// 越过末尾是合法的空读
	resp, body, _ = call(t, root, map[string]any{"id": 4, "op": "read", "path": file, "offset": 999, "len": 5}, nil)
	if !resp.Ok || len(body) != 0 {
		t.Fatalf("越过末尾应返回空 body: %+v body=%q", resp, body)
	}

	cases := []struct {
		name   string
		fields map[string]any
		code   string
	}{
		{"len 超 1 MiB", map[string]any{"path": file, "len": maxReadLen + 1}, errTooBig},
		{"len 为负", map[string]any{"path": file, "len": -1}, errInvalid},
		{"offset 为负", map[string]any{"path": file, "offset": -1}, errInvalid},
		{"读目录", map[string]any{"path": filepath.Join(root, "sub")}, errIsDir},
		{"文件不存在", map[string]any{"path": filepath.Join(root, "nope")}, errNotFound},
		{"越界", map[string]any{"path": "/etc/passwd"}, errInvalid},
	}
	for i, tc := range cases {
		fields := map[string]any{"id": 10 + i, "op": "read"}
		for k, v := range tc.fields {
			fields[k] = v
		}
		resp, _, _ := call(t, root, fields, nil)
		if resp.Ok || resp.Err != tc.code {
			t.Fatalf("%s: 期望 %s，实际 %+v", tc.name, tc.code, resp)
		}
	}
}

func TestWrite(t *testing.T) {
	_, root := newTestRoot(t)
	target := filepath.Join(root, "new.txt")

	resp, _, _ := call(t, root, map[string]any{"id": 1, "op": "write", "path": target}, []byte("abc"))
	if !resp.Ok || resp.Size == nil || *resp.Size != 3 {
		t.Fatalf("write 失败: %+v", resp)
	}
	if got, _ := os.ReadFile(target); string(got) != "abc" {
		t.Fatalf("写入内容 = %q", got)
	}

	// overwrite=false 时目标已存在
	resp, _, _ = call(t, root, map[string]any{"id": 2, "op": "write", "path": target, "overwrite": false}, []byte("x"))
	if resp.Ok || resp.Err != errExist {
		t.Fatalf("overwrite=false 应报 EEXIST: %+v", resp)
	}
	if got, _ := os.ReadFile(target); string(got) != "abc" {
		t.Fatalf("失败写入不应改动文件: %q", got)
	}

	// 默认覆盖 + mode
	resp, _, _ = call(t, root, map[string]any{"id": 3, "op": "write", "path": target, "mode": 0o600}, []byte("defg"))
	if !resp.Ok || *resp.Size != 4 {
		t.Fatalf("覆盖写失败: %+v", resp)
	}
	if got, _ := os.ReadFile(target); string(got) != "defg" {
		t.Fatalf("覆盖写内容 = %q", got)
	}

	cases := []struct {
		name   string
		fields map[string]any
		body   []byte
		code   string
	}{
		{"父目录不存在", map[string]any{"path": filepath.Join(root, "no", "such", "f.txt")}, []byte("x"), errNotFound},
		{"目标是目录", map[string]any{"path": filepath.Join(root, "sub")}, []byte("x"), errIsDir},
		{"越界", map[string]any{"path": "/tmp/agent-escape.txt"}, []byte("x"), errInvalid},
		{"mode 为负", map[string]any{"path": filepath.Join(root, "m.txt"), "mode": -1}, []byte("x"), errInvalid},
	}
	for i, tc := range cases {
		fields := map[string]any{"id": 20 + i, "op": "write"}
		for k, v := range tc.fields {
			fields[k] = v
		}
		resp, _, _ := call(t, root, fields, tc.body)
		if resp.Ok || resp.Err != tc.code {
			t.Fatalf("%s: 期望 %s，实际 %+v", tc.name, tc.code, resp)
		}
	}
}

func TestWriteIsAtomic(t *testing.T) {
	_, root := newTestRoot(t)
	target := filepath.Join(root, "atomic.txt")

	// 反复覆盖：每次成功之后读到的必须就是这次写的完整内容，
	// 不能出现「文件还在、内容只写了一半」的中间态。
	for i := 0; i < 3; i++ {
		body := bytes.Repeat([]byte("x"), 4096*(i+1))
		resp, _, _ := call(t, root, map[string]any{"id": i + 1, "op": "write", "path": target}, body)
		if !resp.Ok {
			t.Fatalf("第 %d 次写入失败: %+v", i+1, resp)
		}
		got, err := os.ReadFile(target)
		if err != nil || !bytes.Equal(got, body) {
			t.Fatalf("第 %d 次写入后内容不完整: err=%v len=%d", i+1, err, len(got))
		}
	}

	// 临时文件不能留在目录里：留了就是每次写都往用户工作区堆垃圾。
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp.") {
			t.Fatalf("残留临时文件: %s", e.Name())
		}
	}
}

func TestChmodAndCopy(t *testing.T) {
	_, root := newTestRoot(t)
	src := filepath.Join(root, "src.txt")
	if resp, _, _ := call(t, root, map[string]any{"id": 1, "op": "write", "path": src}, []byte("payload")); !resp.Ok {
		t.Fatalf("准备源文件失败: %+v", resp)
	}

	// chmod：先改成 600 再读回权限位
	resp, _, _ := call(t, root, map[string]any{"id": 2, "op": "chmod", "path": src, "mode": 0o600}, nil)
	if !resp.Ok {
		t.Fatalf("chmod 失败: %+v", resp)
	}
	info, err := os.Stat(src)
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("chmod 后权限 = %v", info.Mode().Perm())
	}
	// setuid 位不应当被写进文件：只取 0o777
	resp, _, _ = call(t, root, map[string]any{"id": 3, "op": "chmod", "path": src, "mode": 0o4755}, nil)
	if !resp.Ok {
		t.Fatalf("chmod 高位失败: %+v", resp)
	}
	if info, _ := os.Stat(src); info.Mode().Perm() != 0o755 {
		t.Fatalf("chmod 高位应被截成 0755，实际 %v", info.Mode().Perm())
	}
	if resp, _, _ := call(t, root, map[string]any{"id": 4, "op": "chmod", "path": src}, nil); resp.Err != errInvalid {
		t.Fatalf("缺 mode 应报 EINVAL: %+v", resp)
	}

	// copy：内容一致、不残留 tmp
	dst := filepath.Join(root, "dst.txt")
	resp, _, _ = call(t, root, map[string]any{"id": 5, "op": "copy", "path": src, "to": dst}, nil)
	if !resp.Ok {
		t.Fatalf("copy 失败: %+v", resp)
	}
	if got, _ := os.ReadFile(dst); string(got) != "payload" {
		t.Fatalf("copy 后内容 = %q", got)
	}
	entries, _ := os.ReadDir(root)
	for _, e := range entries {
		if strings.Contains(e.Name(), ".tmp.") {
			t.Fatalf("copy 残留临时文件: %s", e.Name())
		}
	}

	// overwrite=false 且目标已存在
	if resp, _, _ := call(t, root, map[string]any{"id": 6, "op": "copy", "path": src, "to": dst, "overwrite": false}, nil); resp.Err != errExist {
		t.Fatalf("copy 到已存在应报 EEXIST: %+v", resp)
	}
	// 源是目录
	sub := filepath.Join(root, "sub")
	call(t, root, map[string]any{"id": 7, "op": "mkdir", "path": sub}, nil)
	if resp, _, _ := call(t, root, map[string]any{"id": 8, "op": "copy", "path": sub, "to": filepath.Join(root, "sub2")}, nil); resp.Err != errIsDir {
		t.Fatalf("copy 目录应报 EISDIR: %+v", resp)
	}
}

func TestMkdirRemoveRename(t *testing.T) {
	_, root := newTestRoot(t)

	resp, _, _ := call(t, root, map[string]any{"id": 1, "op": "mkdir", "path": filepath.Join(root, "n1", "n2")}, nil)
	if !resp.Ok {
		t.Fatalf("mkdir 嵌套: %+v", resp)
	}
	if info, err := os.Stat(filepath.Join(root, "n1", "n2")); err != nil || !info.IsDir() {
		t.Fatalf("目录未创建: %v", err)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 2, "op": "mkdir", "path": filepath.Join(root, "n1", "n2")}, nil)
	if !resp.Ok {
		t.Fatalf("mkdir 已存在目录应成功: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 3, "op": "mkdir", "path": filepath.Join(root, "a.txt")}, nil)
	if resp.Ok || resp.Err != errExist {
		t.Fatalf("mkdir 已存在文件应报 EEXIST: %+v", resp)
	}

	// 非空目录不带 recursive：协议没有 ENOTEMPTY，归到 EEXIST
	resp, _, _ = call(t, root, map[string]any{"id": 4, "op": "remove", "path": filepath.Join(root, "sub")}, nil)
	if resp.Ok || resp.Err != errExist {
		t.Fatalf("删非空目录应报 EEXIST: %+v", resp)
	}
	if _, err := os.Stat(filepath.Join(root, "sub", "s.txt")); err != nil {
		t.Fatalf("失败删除不应动文件: %v", err)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 5, "op": "remove", "path": filepath.Join(root, "sub"), "recursive": true}, nil)
	if !resp.Ok {
		t.Fatalf("递归删除: %+v", resp)
	}
	if _, err := os.Stat(filepath.Join(root, "sub")); !os.IsNotExist(err) {
		t.Fatalf("目录应已删除: %v", err)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 6, "op": "remove", "path": filepath.Join(root, "not-there")}, nil)
	if resp.Ok || resp.Err != errNotFound {
		t.Fatalf("删不存在的路径应报 ENOENT: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 7, "op": "remove", "path": root, "recursive": true}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("删根目录应报 EINVAL: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 8, "op": "remove", "path": root}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("删根目录（非递归）也应报 EINVAL: %+v", resp)
	}
	if info, err := os.Stat(root); err != nil || !info.IsDir() {
		t.Fatalf("根目录必须还在: %v", err)
	}

	// rename
	src := filepath.Join(root, "a.txt")
	dst := filepath.Join(root, "renamed.txt")
	resp, _, _ = call(t, root, map[string]any{"id": 9, "op": "rename", "path": src, "to": dst}, nil)
	if !resp.Ok {
		t.Fatalf("rename: %+v", resp)
	}
	if _, err := os.Stat(dst); err != nil {
		t.Fatalf("目标不存在: %v", err)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 10, "op": "rename", "path": filepath.Join(root, "nope"), "to": dst}, nil)
	if resp.Ok || resp.Err != errNotFound {
		t.Fatalf("rename 源不存在应报 ENOENT: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 11, "op": "rename", "path": dst}, nil)
	if resp.Ok || resp.Err != errInvalid || !strings.Contains(resp.Msg, "to is required") {
		t.Fatalf("rename 缺 to 应报 EINVAL: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 12, "op": "rename", "path": dst, "to": "/tmp/pwned.txt"}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("rename 目标越界应报 EINVAL: %+v", resp)
	}
}

func TestHash(t *testing.T) {
	_, root := newTestRoot(t)
	file := filepath.Join(root, "a.txt")
	resp, _, _ := call(t, root, map[string]any{"id": 1, "op": "hash", "path": file}, nil)
	if !resp.Ok || resp.Size == nil || *resp.Size != 12 {
		t.Fatalf("hash 失败: %+v", resp)
	}
	sum := sha256.Sum256([]byte("hello world\n"))
	want := "sha256:" + hex.EncodeToString(sum[:])
	if resp.Hash != want {
		t.Fatalf("hash = %q，期望 %q", resp.Hash, want)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 2, "op": "hash", "path": filepath.Join(root, "sub")}, nil)
	if resp.Ok || resp.Err != errIsDir {
		t.Fatalf("hash 目录应报 EISDIR: %+v", resp)
	}
}

func TestList(t *testing.T) {
	sb, root := newTestRoot(t)
	if err := os.Symlink(filepath.Join(root, "a.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Fatal(err)
	}

	resp, body, _ := call(t, root, map[string]any{"id": 1, "op": "list", "path": root}, nil)
	if !resp.Ok {
		t.Fatalf("list 失败: %+v", resp)
	}
	// 默认 maxDepth=1：只列本层，按名字升序
	if got := listNames(t, body); strings.Join(got, ",") != "a.txt,empty,link.txt,sub" {
		t.Fatalf("默认 list = %v", got)
	}

	var entries []listEntry
	if err := json.Unmarshal(body, &entries); err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		switch e.Name {
		case "sub":
			if e.Type != 1 || e.Size != 0 {
				t.Fatalf("目录条目应为 t=1 s=0: %+v", e)
			}
		case "link.txt":
			if e.Type != 2 {
				t.Fatalf("符号链接应为 t=2: %+v", e)
			}
		case "a.txt":
			if e.Type != 0 || e.Size != 12 || e.Mtime == 0 {
				t.Fatalf("文件条目不对: %+v", e)
			}
		}
	}

	// maxDepth=2：多一层
	_, body, _ = call(t, root, map[string]any{"id": 2, "op": "list", "path": root, "maxDepth": 2}, nil)
	if got := strings.Join(listNames(t, body), ","); got != "a.txt,empty,link.txt,sub,deep,s.txt" {
		t.Fatalf("maxDepth=2 = %s", got)
	}

	// maxDepth=0 表示不限层数
	_, body, _ = call(t, root, map[string]any{"id": 3, "op": "list", "path": root, "maxDepth": 0}, nil)
	if got := strings.Join(listNames(t, body), ","); got != "a.txt,empty,link.txt,sub,deep,d.txt,s.txt" {
		t.Fatalf("maxDepth=0 = %s", got)
	}

	// recursive 且显式给 maxDepth 时，maxDepth 生效
	_, body, _ = call(t, root, map[string]any{"id": 4, "op": "list", "path": root, "recursive": true, "maxDepth": 1}, nil)
	if got := strings.Join(listNames(t, body), ","); got != "a.txt,empty,link.txt,sub" {
		t.Fatalf("recursive+maxDepth=1 = %s", got)
	}

	resp, _, _ = call(t, root, map[string]any{"id": 5, "op": "list", "path": root, "maxDepth": -1}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("maxDepth 为负应报 EINVAL: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 6, "op": "list", "path": filepath.Join(sb.root, "a.txt")}, nil)
	if resp.Ok || resp.Err != errNotDir {
		t.Fatalf("list 文件应报 ENOTDIR: %+v", resp)
	}
	resp, _, _ = call(t, root, map[string]any{"id": 7, "op": "list", "path": filepath.Join(root, "nope")}, nil)
	if resp.Ok || resp.Err != errNotFound {
		t.Fatalf("list 缺失应报 ENOENT: %+v", resp)
	}
}

func TestDispatchErrors(t *testing.T) {
	_, root := newTestRoot(t)
	resp, _, _ := call(t, root, map[string]any{"id": 1, "op": "bogus"}, nil)
	if resp.Ok || resp.Err != errUnsupported {
		t.Fatalf("未知 op 应报 EUNSUPPORTED: %+v", resp)
	}
	for i, op := range []string{"stat", "list", "read", "write", "mkdir", "remove", "hash"} {
		resp, _, _ := call(t, root, map[string]any{"id": 10 + i, "op": op}, nil)
		if resp.Ok || resp.Err != errInvalid {
			t.Fatalf("%s 缺 path 应报 EINVAL: %+v", op, resp)
		}
	}
	resp, _, _ = call(t, root, map[string]any{"id": 30, "op": ""}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("缺 op 应报 EINVAL: %+v", resp)
	}
}

func TestMultipleRequestsKeepOrder(t *testing.T) {
	_, root := newTestRoot(t)
	var stream []byte
	for i := 1; i <= 3; i++ {
		stream = append(stream, reqFrame(t, map[string]any{
			"id": i, "op": "stat", "path": filepath.Join(root, "a.txt"),
		}, nil)...)
	}
	code, frames, _ := runAgent(t, root, stream)
	if code != 0 || len(frames) != 3 {
		t.Fatalf("exit=%d frames=%d", code, len(frames))
	}
	for i, f := range frames {
		if f.resp.ID != i+1 || !f.resp.Ok {
			t.Fatalf("第 %d 帧不对: %+v", i+1, f.resp)
		}
	}
}

func TestLogLine(t *testing.T) {
	_, root := newTestRoot(t)
	file := filepath.Join(root, "a.txt")
	_, _, logs := call(t, root, map[string]any{"id": 12, "op": "read", "path": file, "len": 5}, nil)

	lineRe := regexp.MustCompile(`^\[agent\] id=12 op=read path=` + regexp.QuoteMeta(file) +
		` len=5 -> ok size=12 body=5 ms=\d+$`)
	lines := strings.Split(strings.TrimSpace(logs), "\n")
	if len(lines) != 2 {
		t.Fatalf("一次请求应只有 start 行 + 一帧日志，实际 %d 行:\n%s", len(lines), logs)
	}
	if !lineRe.MatchString(lines[1]) {
		t.Fatalf("日志格式不符:\n%s", logs)
	}

	_, _, logs = call(t, root, map[string]any{"id": 13, "op": "stat", "path": filepath.Join(root, "nope")}, nil)
	if !strings.Contains(logs, `-> ENOENT msg="no such file or directory" ms=`) {
		t.Fatalf("失败日志格式不符:\n%s", logs)
	}
	if strings.Contains(logs, "body=") {
		t.Fatalf("失败日志不该有 body=:\n%s", logs)
	}
}

func TestLineWherePathHasNewline(t *testing.T) {
	_, root := newTestRoot(t)
	path := filepath.Join(root, "weird\nname.txt")
	mustWrite(t, path, "x")
	_, _, logs := call(t, root, map[string]any{"id": 1, "op": "stat", "path": path}, nil)
	if got := strings.Count(logs, "\n"); got != 2 { // start 行 + 本帧一行
		t.Fatalf("日志被换行劈开了（%d 行）:\n%s", got, logs)
	}
}

func TestWireErrorReturnsFrameThenExits(t *testing.T) {
	_, root := newTestRoot(t)
	code, frames, logs := runAgent(t, root, append([]byte("XXXX"), 0, 0, 0, 2))
	if code != 2 {
		t.Fatalf("坏 MAGIC 应退出 2，实际 %d", code)
	}
	if len(frames) != 1 || frames[0].resp.Ok || frames[0].resp.Err != errInvalid {
		t.Fatalf("应回一帧 EINVAL: %+v", frames)
	}
	if !strings.Contains(logs, "[agent] fatal invalid frame") {
		t.Fatalf("缺少 fatal 日志:\n%s", logs)
	}
}

func TestCleanEOFExitsZero(t *testing.T) {
	_, root := newTestRoot(t)
	code, frames, logs := runAgent(t, root, nil)
	if code != 0 || len(frames) != 0 {
		t.Fatalf("空输入应 0 退出且无帧: code=%d frames=%d", code, len(frames))
	}
	if !strings.Contains(logs, "[agent] start") {
		t.Fatalf("缺少 start 日志:\n%s", logs)
	}
}

func TestMissingRootIsRejected(t *testing.T) {
	var stdout, stderr strings.Builder
	code := run(nil, strings.NewReader(""), &stdout, &stderr)
	if code != 2 {
		t.Fatalf("缺 -root 应退出 2，实际 %d", code)
	}
	if stdout.Len() != 0 {
		t.Fatalf("stdout 必须干净: %q", stdout.String())
	}
	if !strings.Contains(stderr.String(), "-root is required") {
		t.Fatalf("缺少提示:\n%s", stderr.String())
	}
}
