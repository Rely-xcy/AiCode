package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func newGrepRoot(t *testing.T) (*sandbox, string) {
	t.Helper()
	root := t.TempDir()
	mustWrite(t, filepath.Join(root, "hello.txt"), "hello world\n")
	mustWrite(t, filepath.Join(root, "sub", "case.txt"), "line one\nHELLO there\nhello again\n")
	mustWrite(t, filepath.Join(root, "crlf.txt"), "alpha\r\nbeta\r\n")
	mustWrite(t, filepath.Join(root, "long.txt"), strings.Repeat("x", 5000)+"needle\n")
	mustWrite(t, filepath.Join(root, "latin.txt"), "caf\xe9 hello\n")
	mustWrite(t, filepath.Join(root, "nul.txt"), "needle\x00tail\n")
	// 第 2 行超过 bufio 默认缓冲（64 KiB），用来验证长行不会把行号算错。
	mustWrite(t, filepath.Join(root, "big.txt"), "first\n"+strings.Repeat("b", 70000)+"\nneedle tail\n")

	outside := t.TempDir()
	mustWrite(t, filepath.Join(outside, "secret.txt"), "hello from outside\n")
	if err := os.MkdirAll(filepath.Join(root, "empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(root, "hello.txt"), filepath.Join(root, "link_in")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(outside, "secret.txt"), filepath.Join(root, "link_out")); err != nil {
		t.Fatal(err)
	}

	sb, err := newSandbox(root)
	if err != nil {
		t.Fatalf("newSandbox: %v", err)
	}
	return sb, sb.root
}

func grepHits(t *testing.T, body []byte) []grepEntry {
	t.Helper()
	var entries []grepEntry
	if err := json.Unmarshal(body, &entries); err != nil {
		t.Fatalf("grep body 不是合法 JSON 数组: %v (%s)", err, body)
	}
	return entries
}

// locs 把结果压成「相对路径:行号」便于断言，同时暴露遍历顺序。
func locs(t *testing.T, root string, entries []grepEntry) []string {
	t.Helper()
	out := make([]string, 0, len(entries))
	for _, e := range entries {
		rel, err := filepath.Rel(root, e.Path)
		if err != nil {
			t.Fatalf("结果路径 %q 不在 root 内: %v", e.Path, err)
		}
		out = append(out, rel+":"+strconv.Itoa(e.Line))
	}
	return out
}

func TestGrepTree(t *testing.T) {
	_, root := newGrepRoot(t)

	resp, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": root, "pattern": "hello"}, nil)
	if !resp.Ok {
		t.Fatalf("grep 失败: %+v", resp)
	}
	// 符号链接不跟：link_in 指向 hello.txt，但结果里只应出现 hello.txt 自己。
	if got := strings.Join(locs(t, root, grepHits(t, body)), ","); got != "hello.txt:1,latin.txt:1,sub/case.txt:3" {
		t.Fatalf("grep hello = %s", got)
	}
	for _, e := range grepHits(t, body) {
		if strings.HasSuffix(e.Path, "link_in") || strings.HasSuffix(e.Path, "link_out") {
			t.Fatalf("遍历时不应跟随符号链接: %s", e.Path)
		}
	}

	// 大小写敏感
	_, body, _ = call(t, root, map[string]any{"id": 2, "op": "grep", "path": root, "pattern": "HELLO"}, nil)
	if got := strings.Join(locs(t, root, grepHits(t, body)), ","); got != "sub/case.txt:2" {
		t.Fatalf("grep HELLO = %s", got)
	}

	// 正则而非字面量
	_, body, _ = call(t, root, map[string]any{"id": 3, "op": "grep", "path": root, "pattern": "h.llo"}, nil)
	if got := strings.Join(locs(t, root, grepHits(t, body)), ","); got != "hello.txt:1,latin.txt:1,sub/case.txt:3" {
		t.Fatalf("grep 正则 = %s", got)
	}
}

func TestGrepLineText(t *testing.T) {
	_, root := newGrepRoot(t)

	// CRLF 的 \r 不能留在 t 里
	_, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": filepath.Join(root, "crlf.txt"), "pattern": "alpha"}, nil)
	hits := grepHits(t, body)
	if len(hits) != 1 || hits[0].Text != "alpha" {
		t.Fatalf("CRLF 行文本 = %+v", hits)
	}

	// 超过 4096 字节的行只保留前 4096 字节，匹配仍按整行
	_, body, _ = call(t, root, map[string]any{"id": 2, "op": "grep", "path": filepath.Join(root, "long.txt"), "pattern": "needle"}, nil)
	hits = grepHits(t, body)
	if len(hits) != 1 {
		t.Fatalf("超长行应能匹配: %+v", hits)
	}
	if len(hits[0].Text) != maxGrepTextLen {
		t.Fatalf("行文本长度 = %d，期望 %d", len(hits[0].Text), maxGrepTextLen)
	}

	// 非法 UTF-8 转成 U+FFFD，保证 body 是合法 JSON 字符串
	_, body, _ = call(t, root, map[string]any{"id": 3, "op": "grep", "path": filepath.Join(root, "latin.txt"), "pattern": "caf"}, nil)
	hits = grepHits(t, body)
	if len(hits) != 1 || hits[0].Text != "caf\uFFFD hello" {
		t.Fatalf("非法 UTF-8 行 = %+v", hits)
	}
}

func TestGrepLineNumbersAcrossLongLines(t *testing.T) {
	_, root := newGrepRoot(t)
	_, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": filepath.Join(root, "big.txt"), "pattern": "needle"}, nil)
	hits := grepHits(t, body)
	if len(hits) != 1 || hits[0].Line != 3 {
		t.Fatalf("长行之后的匹配行号不对: %+v", hits)
	}
}

// 含 NUL 的文件照常按行搜：协议没有「二进制跳过」的约定，
// 静默跳过会让客户端以为文件里没有匹配。
func TestGrepDoesNotSkipNulBytes(t *testing.T) {
	_, root := newGrepRoot(t)
	_, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": filepath.Join(root, "nul.txt"), "pattern": "needle"}, nil)
	hits := grepHits(t, body)
	if len(hits) != 1 || hits[0].Text != "needle\x00tail" {
		t.Fatalf("含 NUL 的行 = %+v", hits)
	}
}

func TestGrepMaxResults(t *testing.T) {
	_, root := newGrepRoot(t)

	_, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": root, "pattern": "hello", "maxResults": 1}, nil)
	if got := len(grepHits(t, body)); got != 1 {
		t.Fatalf("maxResults=1 命中 %d 条", got)
	}

	// 0 与缺省同义（200），不是「一条都不要」
	_, body, _ = call(t, root, map[string]any{"id": 2, "op": "grep", "path": root, "pattern": "hello", "maxResults": 0}, nil)
	if got := len(grepHits(t, body)); got != 3 {
		t.Fatalf("maxResults=0 应等同缺省，命中 %d 条", got)
	}

	resp, _, _ := call(t, root, map[string]any{"id": 3, "op": "grep", "path": root, "pattern": "hello", "maxResults": -1}, nil)
	if resp.Ok || resp.Err != errInvalid {
		t.Fatalf("负 maxResults 应报 EINVAL: %+v", resp)
	}
}

func TestGrepEmptyAndErrors(t *testing.T) {
	_, root := newGrepRoot(t)

	// 空结果必须是 []，不能是 null：客户端会按数组解析
	_, body, _ := call(t, root, map[string]any{"id": 1, "op": "grep", "path": filepath.Join(root, "empty"), "pattern": "x"}, nil)
	if string(body) != "[]" {
		t.Fatalf("空结果 body = %q", body)
	}

	cases := []struct {
		name   string
		fields map[string]any
		code   string
	}{
		{"缺 pattern", map[string]any{"path": root}, errInvalid},
		{"正则非法", map[string]any{"path": root, "pattern": "a("}, errInvalid},
		{"路径不存在", map[string]any{"path": filepath.Join(root, "nope"), "pattern": "a"}, errNotFound},
		{"越界", map[string]any{"path": "/etc", "pattern": "a"}, errInvalid},
	}
	for i, tc := range cases {
		fields := map[string]any{"id": 10 + i, "op": "grep"}
		for k, v := range tc.fields {
			fields[k] = v
		}
		resp, _, _ := call(t, root, fields, nil)
		if resp.Ok || resp.Err != tc.code {
			t.Fatalf("%s: 期望 %s，实际 %+v", tc.name, tc.code, resp)
		}
	}
}
