package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func wantCode(t *testing.T, err error, code string) {
	t.Helper()
	var pe *protoError
	if !errors.As(err, &pe) {
		t.Fatalf("期望 *protoError(%s)，实际 %T %v", code, err, err)
	}
	if pe.code != code {
		t.Fatalf("错误码 = %s (%s)，期望 %s", pe.code, pe.msg, code)
	}
}

func TestResolveInsideRoot(t *testing.T) {
	sb, root := newTestRoot(t)
	cases := map[string]string{
		root:                            root,
		root + "/":                      root,
		root + "/a.txt":                 filepath.Join(root, "a.txt"),
		root + "/./sub/../a.txt":        filepath.Join(root, "a.txt"),
		root + "/sub/deep/d.txt":        filepath.Join(root, "sub", "deep", "d.txt"),
		root + "/not-created-yet.txt":   filepath.Join(root, "not-created-yet.txt"),
		root + "/n1/n2/n3/not-here.txt": filepath.Join(root, "n1", "n2", "n3", "not-here.txt"),
	}
	for in, want := range cases {
		got, err := sb.resolve(in)
		if err != nil {
			t.Fatalf("resolve(%q) 失败: %v", in, err)
		}
		if got != want {
			t.Fatalf("resolve(%q) = %q，期望 %q", in, got, want)
		}
	}
}

func TestResolveRejectsEscapes(t *testing.T) {
	sb, root := newTestRoot(t)
	base := filepath.Dir(root)

	cases := map[string]string{
		"..":      root + "/../etc/passwd",
		"绝对路径在根外": "/etc/passwd",
		"同级同前缀目录": filepath.Join(base, "root2", "x.txt"),
		"相对路径":    "sub/s.txt",
		"空路径":     "",
		"多级回退":    root + "/sub/../../outside.txt",
		"home 展开后仍在根外（home=根上层的父）": base + "/x",
	}
	for name, in := range cases {
		if _, err := sb.resolve(in); err == nil {
			t.Fatalf("%s: resolve(%q) 应失败", name, in)
		} else {
			wantCode(t, err, errInvalid)
		}
	}
}

// 同级同前缀目录是最容易被裸 HasPrefix 放过的越界：/x/root2 以 /x/root 开头。
func TestResolveSiblingPrefixIsRejected(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "root")
	sibling := filepath.Join(base, "root2")
	for _, dir := range []string{root, sibling} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	sb, err := newSandbox(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sb.resolve(filepath.Join(sibling, "x")); err == nil {
		t.Fatal("root2 不是 root 的子路径，应被拒绝")
	}
}

func TestResolveSymlinks(t *testing.T) {
	sb, root := newTestRoot(t)
	outside := t.TempDir()
	mustWrite(t, filepath.Join(outside, "secret.txt"), "secret")

	// 根内链接：解析到目标
	if err := os.Symlink(filepath.Join(root, "a.txt"), filepath.Join(root, "link_in")); err != nil {
		t.Fatal(err)
	}
	got, err := sb.resolve(filepath.Join(root, "link_in"))
	if err != nil {
		t.Fatalf("根内符号链接应可用: %v", err)
	}
	if got != filepath.Join(root, "a.txt") {
		t.Fatalf("解析结果 = %q", got)
	}

	// 指向根外的文件链接
	if err := os.Symlink(filepath.Join(outside, "secret.txt"), filepath.Join(root, "link_out")); err != nil {
		t.Fatal(err)
	}
	_, err = sb.resolve(filepath.Join(root, "link_out"))
	wantCode(t, err, errInvalid)
	if !strings.Contains(err.Error(), "escapes root") {
		t.Fatalf("错误信息应点明越界: %v", err)
	}

	// 指向根外的目录链接，且访问其子路径
	if err := os.Symlink(outside, filepath.Join(root, "dirlink_out")); err != nil {
		t.Fatal(err)
	}
	_, err = sb.resolve(filepath.Join(root, "dirlink_out", "new-file.txt"))
	wantCode(t, err, errInvalid)

	// 悬空链接指向根外：按链接解析会把「不存在的尾部」直接拼回去，
	// 之后的 O_CREATE 就会在根外建文件，所以这里必须拒绝。
	if err := os.Symlink(filepath.Join(outside, "ghost.txt"), filepath.Join(root, "dangling_out")); err != nil {
		t.Fatal(err)
	}
	_, err = sb.resolve(filepath.Join(root, "dangling_out"))
	wantCode(t, err, errInvalid)
}

func TestResolveSymlinkLoop(t *testing.T) {
	sb, root := newTestRoot(t)
	if err := os.Symlink(filepath.Join(root, "loop_b"), filepath.Join(root, "loop_a")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "loop_a"), filepath.Join(root, "loop_b")); err != nil {
		t.Fatal(err)
	}
	_, err := sb.resolve(filepath.Join(root, "loop_a"))
	wantCode(t, err, errInvalid)
	if !strings.Contains(err.Error(), "too many levels") {
		t.Fatalf("应报符号链接层数超限: %v", err)
	}
}

func TestResolveHomeExpansion(t *testing.T) {
	sb, root := newTestRoot(t)
	sb.home = root
	got, err := sb.resolve("~/sub/s.txt")
	if err != nil {
		t.Fatalf("~ 展开失败: %v", err)
	}
	if got != filepath.Join(root, "sub", "s.txt") {
		t.Fatalf("~ 展开结果 = %q", got)
	}

	// home 不在 root 内时必须拒绝：展开不能成为越界的后门。
	sb.home = filepath.Dir(root)
	if _, err := sb.resolve("~/sub/s.txt"); err == nil {
		t.Fatal("home 在根外时应拒绝")
	}
}

func TestResolveThroughRootAlias(t *testing.T) {
	base := t.TempDir()
	realRoot := filepath.Join(base, "real")
	alias := filepath.Join(base, "alias")
	if err := os.MkdirAll(filepath.Join(realRoot, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	mustWrite(t, filepath.Join(realRoot, "sub", "f.txt"), "x")
	if err := os.Symlink(realRoot, alias); err != nil {
		t.Fatal(err)
	}

	sb, err := newSandbox(alias)
	if err != nil {
		t.Fatalf("newSandbox(alias): %v", err)
	}
	if sb.root != realRoot {
		t.Fatalf("root 应解析成真实路径，实际 %q", sb.root)
	}

	// 客户端拿启动参数拼路径（带别名）必须能正常工作
	got, err := sb.resolve(filepath.Join(alias, "sub", "f.txt"))
	if err != nil {
		t.Fatalf("别名路径应可用: %v", err)
	}
	if got != filepath.Join(realRoot, "sub", "f.txt") {
		t.Fatalf("别名路径解析结果 = %q", got)
	}

	// 别名不能成为越界后门
	if _, err := sb.resolve(filepath.Join(alias, "..", "etc", "passwd")); err == nil {
		t.Fatal("别名下的 ../ 应被拒绝")
	}
	outside := t.TempDir()
	if err := os.Symlink(filepath.Join(outside, "s.txt"), filepath.Join(realRoot, "link_out")); err != nil {
		t.Fatal(err)
	}
	if _, err := sb.resolve(filepath.Join(alias, "link_out")); err == nil {
		t.Fatal("别名下指向根外的符号链接应被拒绝")
	}
	if err := os.Symlink(filepath.Join(outside, "ghost.txt"), filepath.Join(realRoot, "dangling_out")); err != nil {
		t.Fatal(err)
	}
	if _, err := sb.resolve(filepath.Join(alias, "dangling_out")); err == nil {
		t.Fatal("别名下指向根外的悬空链接应被拒绝")
	}
}

func TestContainsBoundary(t *testing.T) {
	sb := &sandbox{root: "/a/b"}
	for p, want := range map[string]bool{
		"/a/b":    true,
		"/a/b/c":  true,
		"/a/bc":   false,
		"/a":      false,
		"/a/b/..": false,
	} {
		if got := sb.contains(filepath.Clean(p)); got != want {
			t.Fatalf("contains(%q) = %v，期望 %v", p, got, want)
		}
	}
}
