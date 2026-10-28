package main

import (
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"
)

// logger 把每一帧的处理结果写成一行 stderr。
//
// 为什么值得为日志单独写一个类型：stdout 里只有二进制帧，出问题时人眼完全看不见；
// 这一行是唯一能直接 grep/tail 的可观测性来源，字段顺序被 PROTOCOL.md 第 6 节固定，
// Android 端按固定前缀收集，所以不能用 log 包默认的「时间戳 + 前缀」形态。
type logger struct {
	w io.Writer
}

func newLogger(w io.Writer) *logger { return &logger{w: w} }

func (l *logger) start(root, real string, hasRg bool) {
	fmt.Fprintf(
		l.w,
		"[agent] start root=%s real=%s proto=%d hasRg=%t pid=%d\n",
		field(root), field(real), protoVersion, hasRg, os.Getpid(),
	)
}

func (l *logger) fatalf(format string, args ...any) {
	fmt.Fprintf(l.w, "[agent] fatal "+format+"\n", args...)
}

// frame 记录一次成功送达的处理结果。[bodyLen] 是请求 body 的长度（write 用），
// resp.BodyLen 已由 writeFrame 按实际 body 填好。
func (l *logger) frame(req *request, bodyLen int, resp *response, elapsed time.Duration) {
	var b strings.Builder
	b.WriteString("[agent] id=")
	b.WriteString(strconv.Itoa(req.ID))
	b.WriteString(" op=")
	b.WriteString(field(req.Op))
	if req.Path != "" {
		b.WriteString(" path=")
		b.WriteString(field(req.Path))
	}
	if req.To != "" {
		b.WriteString(" to=")
		b.WriteString(field(req.To))
	}
	switch req.Op {
	case "read":
		n := defaultReadLen
		if req.Len != nil {
			n = *req.Len
		}
		b.WriteString(" len=")
		b.WriteString(strconv.Itoa(n))
	case "write":
		b.WriteString(" len=")
		b.WriteString(strconv.Itoa(bodyLen))
	}
	if req.Offset != nil && *req.Offset != 0 {
		b.WriteString(" offset=")
		b.WriteString(strconv.FormatInt(*req.Offset, 10))
	}
	if req.MaxDepth != nil {
		b.WriteString(" maxDepth=")
		b.WriteString(strconv.Itoa(*req.MaxDepth))
	}
	if req.Recursive {
		b.WriteString(" recursive=true")
	}
	if req.Op == "grep" {
		b.WriteString(" pattern=")
		b.WriteString(strconv.Quote(field(req.Pattern)))
	}
	if req.MaxResults != nil {
		b.WriteString(" maxResults=")
		b.WriteString(strconv.Itoa(*req.MaxResults))
	}
	if resp.Ok {
		b.WriteString(" -> ok")
		if resp.Size != nil {
			b.WriteString(" size=")
			b.WriteString(strconv.FormatInt(*resp.Size, 10))
		}
		b.WriteString(" body=")
		b.WriteString(strconv.Itoa(resp.BodyLen))
	} else {
		b.WriteString(" -> ")
		b.WriteString(resp.Err)
		b.WriteString(" msg=")
		b.WriteString(strconv.Quote(resp.Msg))
	}
	b.WriteString(" ms=")
	b.WriteString(strconv.FormatInt(elapsed.Milliseconds(), 10))
	b.WriteByte('\n')
	io.WriteString(l.w, b.String())
}

// frameFailure 记录帧结构错误。此时 header 没解出来，op 与路径都无从谈起，
// 只留 id（可能为 0）与错误码，便于对照客户端日志定位是哪个请求写坏了流。
func (l *logger) frameFailure(fe *frameError, elapsed time.Duration) {
	fmt.Fprintf(
		l.w,
		"[agent] id=%d op= -> %s msg=%s ms=%d\n",
		fe.id, fe.code, strconv.Quote(clampMsg(fe.msg)), elapsed.Milliseconds(),
	)
}

// field 把换行转义掉。路径里出现 \n 时，不转义会把一行日志劈成两行，
// 下游按行 grep 的收集逻辑就会错位。
func field(s string) string {
	if !strings.ContainsAny(s, "\r\n") {
		return s
	}
	r := strings.NewReplacer("\r", "\\r", "\n", "\\n")
	return r.Replace(s)
}
