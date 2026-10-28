package main

import (
	"bytes"
	"encoding/json"
	"unicode/utf8"
)

// 线格式常量。数值与 PROTOCOL.md 一致，改动等于改协议：客户端按同一组上限
// 校验长度，两侧不一致时会把 body 字节读成下一帧的 MAGIC，整条流无法自愈。
const (
	protoVersion = 1
	magic        = "AICD"

	fixedHeadBytes = 8
	maxHeaderBytes = 1 << 20  // 1 MiB
	maxBodyBytes   = 64 << 20 // 64 MiB

	defaultReadLen = 64 << 10 // 64 KiB
	maxReadLen     = 1 << 20  // 1 MiB

	defaultMaxDepth   = 1
	defaultMaxResults = 200

	defaultFileMode = 0o644
	defaultDirMode  = 0o755

	maxErrMsgLen   = 120
	maxGrepTextLen = 4096
)

// 错误码。只用 PROTOCOL.md 第 4 节列出的那几个，避免客户端拿到未知码。
const (
	errNotFound    = "ENOENT"
	errAccess      = "EACCES"
	errIsDir       = "EISDIR"
	errNotDir      = "ENOTDIR"
	errExist       = "EEXIST"
	errInvalid     = "EINVAL"
	errTooBig      = "ETOOBIG"
	errIO          = "EIO"
	errUnsupported = "EUNSUPPORTED"
)

// request 是请求头。指针字段用来区分「没给」与「给了零值」：
// len=0 表示读 0 字节，而 len 缺省才是 65536；两者混用会让读接口静默返回空。
type request struct {
	ID         int    `json:"id"`
	Op         string `json:"op"`
	Path       string `json:"path"`
	Offset     *int64 `json:"offset"`
	Len        *int   `json:"len"`
	Mode       *int   `json:"mode"`
	Overwrite  *bool  `json:"overwrite"`
	MaxDepth   *int   `json:"maxDepth"`
	Recursive  bool   `json:"recursive"`
	Pattern    string `json:"pattern"`
	MaxResults *int   `json:"maxResults"`
	To         string `json:"to"`
	BodyLen    int64  `json:"bodyLen"`
}

// response 是响应头。字段顺序照 PROTOCOL.md 第 2 节，便于人读原始帧。
// bodyLen 不带 omitempty：客户端靠它决定后面读多少字节，缺失会让它按 0 处理。
type response struct {
	ID      int    `json:"id"`
	Ok      bool   `json:"ok"`
	Err     string `json:"err,omitempty"`
	Msg     string `json:"msg,omitempty"`
	Size    *int64 `json:"size,omitempty"`
	Mtime   *int64 `json:"mtime,omitempty"`
	IsDir   *bool  `json:"isDir,omitempty"`
	Perm    string `json:"perm,omitempty"`
	Hash    string `json:"hash,omitempty"`
	Proto   *int   `json:"proto,omitempty"`
	Go      string `json:"go,omitempty"`
	OS      string `json:"os,omitempty"`
	Arch    string `json:"arch,omitempty"`
	HasRg   *bool  `json:"hasRg,omitempty"`
	Home    string `json:"home,omitempty"`
	CanHash *bool  `json:"canHash,omitempty"`
	BodyLen int    `json:"bodyLen"`
}

// listEntry 是 list 的 body 元素。字段名必须短：目录大时 body 直接进 64 MiB 上限。
type listEntry struct {
	Name  string `json:"n"`
	Type  int    `json:"t"`
	Size  int64  `json:"s"`
	Mtime int64  `json:"m"`
}

// grepEntry 是 grep 的 body 元素。
type grepEntry struct {
	Path string `json:"p"`
	Line int    `json:"l"`
	Text string `json:"t"`
}

// protoError 是已归类到协议错误码的失败，ops 层只返回它，不再返回裸 error。
type protoError struct {
	code string
	msg  string
}

func (e *protoError) Error() string { return e.code + ": " + e.msg }

func newErr(code, msg string) *protoError { return &protoError{code: code, msg: msg} }

// encodeBody 序列化 list/grep 的 body。关掉 HTML 转义：行内容里的 < > & 原样保留，
// json.Marshal 默认会转成 \u003c，虽然解码等价，但日志与抓包时无法肉眼比对。
// Encoder 会补一个换行，去掉以保证 body 长度等于 JSON 自身长度。
func encodeBody(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}

// clampMsg 保证 msg 不超过协议约定的 120 字符；按 rune 截断，避免切碎 UTF-8。
func clampMsg(msg string) string {
	if utf8.RuneCountInString(msg) <= maxErrMsgLen {
		return msg
	}
	runes := []rune(msg)
	return string(runes[:maxErrMsgLen])
}

func intPtr(v int) *int       { return &v }
func int64Ptr(v int64) *int64 { return &v }
func boolPtr(v bool) *bool    { return &v }
