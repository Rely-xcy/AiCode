package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"testing/iotest"
)

// frameBytes 按线格式拼一帧，header 原样放进去（调用方自己保证 bodyLen 正确）。
func frameBytes(t *testing.T, header string, body []byte) []byte {
	t.Helper()
	var out bytes.Buffer
	out.WriteString(magic)
	var head [4]byte
	binary.BigEndian.PutUint32(head[:], uint32(len(header)))
	out.Write(head[:])
	out.WriteString(header)
	out.Write(body)
	return out.Bytes()
}

// reqFrame 用 map 拼请求头，省掉手写 JSON 的转义问题。
func reqFrame(t *testing.T, fields map[string]any, body []byte) []byte {
	t.Helper()
	h := map[string]any{}
	for k, v := range fields {
		h[k] = v
	}
	h["bodyLen"] = len(body)
	header, err := json.Marshal(h)
	if err != nil {
		t.Fatalf("marshal header: %v", err)
	}
	return frameBytes(t, string(header), body)
}

type decoded struct {
	resp response
	body []byte
}

// decodeAll 把 stdout 里所有帧解出来，同时校验帧边界完全对齐——
// 多出或少掉一个字节都说明写侧的 bodyLen 算错了。
func decodeAll(t *testing.T, out []byte) []decoded {
	t.Helper()
	var frames []decoded
	for len(out) > 0 {
		if len(out) < fixedHeadBytes {
			t.Fatalf("残帧：只剩 %d 字节", len(out))
		}
		if string(out[:4]) != magic {
			t.Fatalf("MAGIC 不符: %q", out[:4])
		}
		headerLen := binary.BigEndian.Uint32(out[4:8])
		if int(headerLen) > len(out)-fixedHeadBytes {
			t.Fatalf("headerLen %d 超过剩余字节 %d", headerLen, len(out)-fixedHeadBytes)
		}
		header := out[fixedHeadBytes : fixedHeadBytes+int(headerLen)]
		out = out[fixedHeadBytes+int(headerLen):]
		var resp response
		if err := json.Unmarshal(header, &resp); err != nil {
			t.Fatalf("解析响应头失败: %v (%s)", err, header)
		}
		if resp.BodyLen > len(out) {
			t.Fatalf("bodyLen %d 超过剩余字节 %d", resp.BodyLen, len(out))
		}
		frames = append(frames, decoded{resp: resp, body: out[:resp.BodyLen]})
		out = out[resp.BodyLen:]
	}
	return frames
}

// runAgent 跑完整主循环：喂一整个字节流，收集 stdout 帧与 stderr 日志。
func runAgent(t *testing.T, root string, stream []byte) (int, []decoded, string) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	code := run([]string{"-root", root}, bytes.NewReader(stream), &stdout, &stderr)
	return code, decodeAll(t, stdout.Bytes()), stderr.String()
}

// call 发一个请求，断言恰好回一帧，返回响应与日志。
func call(t *testing.T, root string, fields map[string]any, body []byte) (response, []byte, string) {
	t.Helper()
	code, frames, logs := runAgent(t, root, reqFrame(t, fields, body))
	if code != 0 {
		t.Fatalf("exit=%d stderr=%s", code, logs)
	}
	if len(frames) != 1 {
		t.Fatalf("期望 1 帧响应，实际 %d 帧", len(frames))
	}
	return frames[0].resp, frames[0].body, logs
}

func mustWrite(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// newTestRoot 建一棵固定结构的目录树，返回已解析符号链接后的真实 root。
func newTestRoot(t *testing.T) (*sandbox, string) {
	t.Helper()
	base := t.TempDir()
	root := filepath.Join(base, "root")
	mustWrite(t, filepath.Join(root, "a.txt"), "hello world\n")
	mustWrite(t, filepath.Join(root, "sub", "s.txt"), "sub content\n")
	mustWrite(t, filepath.Join(root, "sub", "deep", "d.txt"), "deep\n")
	if err := os.MkdirAll(filepath.Join(root, "empty"), 0o755); err != nil {
		t.Fatal(err)
	}
	sb, err := newSandbox(root)
	if err != nil {
		t.Fatalf("newSandbox: %v", err)
	}
	return sb, sb.root
}

func TestFrameReaderRoundTrip(t *testing.T) {
	stream := append(
		reqFrame(t, map[string]any{"id": 1, "op": "stat", "path": "/x"}, nil),
		reqFrame(t, map[string]any{"id": 2, "op": "read", "path": "/y", "len": 3}, []byte("hello"))...,
	)
	fr := newFrameReader(bytes.NewReader(stream))
	first, body, err := fr.next()
	if err != nil {
		t.Fatalf("第一帧: %v", err)
	}
	if first.ID != 1 || first.Op != "stat" || len(body) != 0 {
		t.Fatalf("第一帧内容不符: %+v body=%q", first, body)
	}
	second, body, err := fr.next()
	if err != nil {
		t.Fatalf("第二帧: %v", err)
	}
	if second.ID != 2 || *second.Len != 3 || string(body) != "hello" {
		t.Fatalf("第二帧内容不符: %+v body=%q", second, body)
	}
	if _, _, err := fr.next(); err != io.EOF {
		t.Fatalf("流结束时应为 io.EOF，实际 %v", err)
	}
}

func TestFrameReaderRejectsOversize(t *testing.T) {
	cases := []struct {
		name     string
		stream   []byte
		wantCode string
	}{
		{
			name: "headerLen 超 1 MiB",
			stream: func() []byte {
				var b bytes.Buffer
				b.WriteString(magic)
				var head [4]byte
				binary.BigEndian.PutUint32(head[:], maxHeaderBytes+1)
				b.Write(head[:])
				return b.Bytes()
			}(),
			wantCode: errTooBig,
		},
		{
			name: "headerLen 为 0",
			stream: func() []byte {
				var b bytes.Buffer
				b.WriteString(magic)
				var head [4]byte
				b.Write(head[:])
				return b.Bytes()
			}(),
			wantCode: errInvalid,
		},
		{
			name:     "bodyLen 超 64 MiB",
			stream:   frameBytes(t, `{"id":9,"op":"write","path":"/x","bodyLen":67108865}`, nil),
			wantCode: errTooBig,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fr := newFrameReader(bytes.NewReader(tc.stream))
			_, _, err := fr.next()
			var fe *frameError
			if !errors.As(err, &fe) {
				t.Fatalf("期望 *frameError，实际 %v", err)
			}
			if fe.code != tc.wantCode {
				t.Fatalf("错误码 = %s，期望 %s", fe.code, tc.wantCode)
			}
		})
	}
}

func TestFrameReaderRejectsBadFrames(t *testing.T) {
	fr := newFrameReader(bytes.NewReader(append([]byte("XXXX"), 0, 0, 0, 2)))
	if _, _, err := fr.next(); err == nil {
		t.Fatal("坏 MAGIC 应报错")
	}

	fr = newFrameReader(bytes.NewReader(frameBytes(t, "{oops", nil)))
	var fe *frameError
	if _, _, err := fr.next(); !errors.As(err, &fe) || fe.code != errInvalid {
		t.Fatalf("坏 header 应报 EINVAL，实际 %v", err)
	}

	// bodyLen 说 8 字节，实际只给 3 字节。
	truncated := frameBytes(t, `{"id":3,"op":"write","path":"/x","bodyLen":8}`, nil)
	truncated = append(truncated, "abc"...)
	fr = newFrameReader(bytes.NewReader(truncated))
	if _, _, err := fr.next(); !errors.As(err, &fe) || fe.code != errIO {
		t.Fatalf("截断 body 应报 EIO，实际 %v", err)
	}
}

// 关键回归：半包（一次只到一个字节）与粘包（两帧一次到齐）都不能丢帧或错位。
func TestFrameReaderHalfAndSticky(t *testing.T) {
	first := reqFrame(t, map[string]any{"id": 1, "op": "stat", "path": "/a"}, nil)
	second := reqFrame(t, map[string]any{"id": 2, "op": "write", "path": "/b"}, []byte("payload"))
	stream := append(append([]byte{}, first...), second...)

	// 一次一个字节到达
	fr := newFrameReader(iotest.OneByteReader(bytes.NewReader(stream)))
	req, _, err := fr.next()
	if err != nil || req.ID != 1 {
		t.Fatalf("半包第一帧: req=%+v err=%v", req, err)
	}
	req, body, err := fr.next()
	if err != nil || req.ID != 2 || string(body) != "payload" {
		t.Fatalf("半包第二帧: req=%+v body=%q err=%v", req, body, err)
	}

	// 两帧粘在一起一次到齐
	fr = newFrameReader(bytes.NewReader(stream))
	if _, _, err := fr.next(); err != nil {
		t.Fatalf("粘包第一帧: %v", err)
	}
	if _, _, err := fr.next(); err != nil {
		t.Fatalf("粘包第二帧: %v", err)
	}
}

func TestWriteFrameLayout(t *testing.T) {
	var buf bytes.Buffer
	bw := bufio.NewWriter(&buf)
	size := int64(7)
	resp := &response{ID: 5, Ok: true, Size: &size}
	if err := writeFrame(bw, resp, []byte("body!")); err != nil {
		t.Fatalf("writeFrame: %v", err)
	}
	raw := buf.Bytes()
	if string(raw[:4]) != magic {
		t.Fatalf("帧头不是 MAGIC: %q", raw[:4])
	}
	headerLen := int(binary.BigEndian.Uint32(raw[4:8]))
	header := raw[8 : 8+headerLen]
	if !bytes.Contains(header, []byte(`"bodyLen":5`)) {
		t.Fatalf("响应头里 bodyLen 不对: %s", header)
	}
	if string(raw[8+headerLen:]) != "body!" {
		t.Fatalf("body 不对: %q", raw[8+headerLen:])
	}
}
