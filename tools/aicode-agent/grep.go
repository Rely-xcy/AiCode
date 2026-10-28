package main

import (
	"bufio"
	"bytes"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"
)

// 单行参与匹配的上限。超过它的行只匹配前 1 MiB，剩余部分继续被消费但不再参与匹配：
// 不设上限时，一个没有换行的巨型文件会被整份读进内存，远端 agent 直接 OOM。
const maxGrepLine = 1 << 20

// errStopWalk 是「结果够了」的内部信号，不对应任何协议错误码。
var errStopWalk = errors.New("grep: reached maxResults")

func (a *agent) opGrep(req *request) (*response, []byte, *protoError) {
	if req.Pattern == "" {
		return nil, nil, newErr(errInvalid, "pattern is required")
	}
	maxResults := defaultMaxResults
	if req.MaxResults != nil {
		if *req.MaxResults < 0 {
			return nil, nil, newErr(errInvalid, "maxResults must not be negative")
		}
		// 0 与缺省同义：客户端把「没设置」序列化成 0 时不至于一条都搜不到。
		if *req.MaxResults > 0 {
			maxResults = *req.MaxResults
		}
	}
	re, err := regexp.Compile(req.Pattern)
	if err != nil {
		return nil, nil, newErr(errInvalid, "invalid regular expression")
	}
	p, err := a.sb.resolve(req.Path)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	info, err := os.Stat(p)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	results := make([]grepEntry, 0, 32)
	switch {
	case info.IsDir():
		if perr := grepTree(p, re, maxResults, &results); perr != nil {
			return nil, nil, perr
		}
	case info.Mode().IsRegular():
		grepFile(p, re, maxResults, &results)
	}
	// 既不是目录也不是普通文件（fifo/socket）：返回空数组而不是去 open，
	// 否则会在「远端路径是个命名管道」时把单线程的主循环挂死。
	out, err := encodeBody(results)
	if err != nil {
		return nil, nil, asProtoErr(err)
	}
	return okResponse(req.ID), out, nil
}

func grepTree(root string, re *regexp.Regexp, maxResults int, out *[]grepEntry) *protoError {
	walkErr := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			if path == root {
				return err
			}
			// 单个子项读不了（权限/竞态删除）就跳过，不因为一个坏节点毁掉整次搜索。
			return nil
		}
		if d.IsDir() {
			return nil
		}
		// 符号链接一律不跟：它可能指向 root 外，跟进去等于绕过边界检查。
		if !d.Type().IsRegular() {
			return nil
		}
		if len(*out) >= maxResults {
			return errStopWalk
		}
		grepFile(path, re, maxResults, out)
		return nil
	})
	if walkErr != nil && !errors.Is(walkErr, errStopWalk) {
		return asProtoErr(walkErr)
	}
	return nil
}

// grepFile 逐行扫描单个文件，结果追加到 out，最多加到 maxResults 条。
// 读不开或读到一半出错都当作「这个文件没有结果」，与 rg 的默认行为一致。
func grepFile(path string, re *regexp.Regexp, maxResults int, out *[]grepEntry) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	br := bufio.NewReaderSize(f, 64<<10)
	lineNo := 0
	for len(*out) < maxResults {
		line, err := readLineLimited(br, maxGrepLine)
		if len(line) == 0 && err != nil {
			return
		}
		lineNo++
		if re.Match(line) {
			*out = append(*out, grepEntry{Path: path, Line: lineNo, Text: lineText(line)})
		}
		if err != nil {
			return
		}
	}
}

// readLineLimited 读一行，最多保留 limit 字节；超出部分继续读掉但不保留。
// 返回 nil + io.EOF 表示文件结束；末行没有换行时返回该行 + io.EOF。
func readLineLimited(br *bufio.Reader, limit int) ([]byte, error) {
	buf := make([]byte, 0, 256)
	for {
		chunk, err := br.ReadSlice('\n')
		if take := limit - len(buf); take > 0 {
			if take > len(chunk) {
				take = len(chunk)
			}
			buf = append(buf, chunk[:take]...)
		}
		switch {
		case err == nil:
			return trimEOL(buf), nil
		case errors.Is(err, bufio.ErrBufferFull):
			continue
		case errors.Is(err, io.EOF):
			if len(buf) == 0 && len(chunk) == 0 {
				return nil, io.EOF
			}
			return trimEOL(buf), io.EOF
		default:
			return nil, err
		}
	}
}

// trimEOL 去掉行尾换行与 CRLF 的 \r。Android 侧直接展示 t，带 \r 会显示成方块。
func trimEOL(line []byte) []byte {
	line = bytes.TrimSuffix(line, []byte("\n"))
	return bytes.TrimSuffix(line, []byte("\r"))
}

// lineText 把行内容转成 JSON 可承载的字符串：非法 UTF-8 换成 U+FFFD（与
// encoding/json 的行为一致，但这里显式做，便于 t 的截断发生在转换之后）。
// 超过 4096 字节的部分截掉，按 rune 边界切，避免尾部留下半个字符。
func lineText(line []byte) string {
	s := strings.ToValidUTF8(string(line), "\uFFFD")
	if len(s) <= maxGrepTextLen {
		return s
	}
	n := maxGrepTextLen
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}
