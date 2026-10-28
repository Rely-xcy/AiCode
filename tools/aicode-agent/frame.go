package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// frameError 表示帧结构本身不合法。这类错误一律致命：headerLen 或 bodyLen 读错之后，
// 无法知道下一个 MAGIC 落在哪个字节，重试只会把后续帧全部解成垃圾。
type frameError struct {
	id   int // 已经读到 header 时才有真实 id，否则为 0
	code string
	msg  string
}

func (e *frameError) Error() string { return e.msg }

// frameReader 从 stdin 逐帧读取。bufio 的作用是「按需取字节」：io.ReadFull 只拿走
// 本次需要的长度，多读出来的字节留在 bufio 的缓冲里，下一帧接着用——粘包不会丢，
// 半包由 io.ReadFull 自己循环补齐。因此全程不能出现 ReadAll / 一次性拷整条流。
type frameReader struct {
	br *bufio.Reader
}

func newFrameReader(r io.Reader) *frameReader {
	return &frameReader{br: bufio.NewReaderSize(r, 64<<10)}
}

// next 读一整帧。返回 io.EOF 表示对端正常关闭（没有残留半帧）；
// 返回 *frameError 表示帧结构非法，调用方应回一帧错误后退出。
func (fr *frameReader) next() (*request, []byte, error) {
	var head [fixedHeadBytes]byte
	if _, err := io.ReadFull(fr.br, head[:]); err != nil {
		if errors.Is(err, io.EOF) {
			return nil, nil, io.EOF
		}
		return nil, nil, &frameError{code: errIO, msg: fmt.Sprintf("truncated frame header: %v", err)}
	}
	if string(head[:4]) != magic {
		return nil, nil, &frameError{code: errInvalid, msg: fmt.Sprintf("bad magic %x", head[:4])}
	}
	headerLen := binary.BigEndian.Uint32(head[4:])
	// headerLen 上限要在分配内存之前检查：否则一个 0xFFFFFFFF 的头部就能让我们申请 4 GiB。
	if headerLen == 0 {
		return nil, nil, &frameError{code: errInvalid, msg: "headerLen must not be zero"}
	}
	if headerLen > maxHeaderBytes {
		return nil, nil, &frameError{
			code: errTooBig,
			msg:  fmt.Sprintf("headerLen out of range: %d", headerLen),
		}
	}
	headerBytes := make([]byte, headerLen)
	if _, err := io.ReadFull(fr.br, headerBytes); err != nil {
		return nil, nil, &frameError{code: errIO, msg: fmt.Sprintf("truncated header: %v", err)}
	}
	req := &request{}
	if err := json.Unmarshal(headerBytes, req); err != nil {
		// 头解析失败时 bodyLen 未知，body 无法跳过，字节流已经错位。
		return nil, nil, &frameError{code: errInvalid, msg: "malformed header json"}
	}
	// 负数也归到这里：header 里 bodyLen 是任意 JSON 数字，不能直接信。
	if req.BodyLen < 0 || req.BodyLen > maxBodyBytes {
		return nil, nil, &frameError{
			id:   req.ID,
			code: errTooBig,
			msg:  fmt.Sprintf("bodyLen out of range: %d", req.BodyLen),
		}
	}
	if req.BodyLen == 0 {
		return req, nil, nil
	}
	body := make([]byte, req.BodyLen)
	if _, err := io.ReadFull(fr.br, body); err != nil {
		return nil, nil, &frameError{
			id:   req.ID,
			code: errIO,
			msg:  fmt.Sprintf("truncated body: %v", err),
		}
	}
	return req, body, nil
}

// writeFrame 把一帧写到 stdout 的缓冲里并立即冲刷：客户端按帧等待响应，
// 攒着不 flush 会表现为「请求超时」。
func writeFrame(bw *bufio.Writer, resp *response, body []byte) error {
	resp.BodyLen = len(body)
	headerBytes, err := json.Marshal(resp)
	if err != nil {
		return err
	}
	if len(headerBytes) > maxHeaderBytes {
		return fmt.Errorf("response header exceeds %d bytes", maxHeaderBytes)
	}
	var head [fixedHeadBytes]byte
	copy(head[:4], magic)
	binary.BigEndian.PutUint32(head[4:], uint32(len(headerBytes)))
	if _, err := bw.Write(head[:]); err != nil {
		return err
	}
	if _, err := bw.Write(headerBytes); err != nil {
		return err
	}
	if len(body) > 0 {
		if _, err := bw.Write(body); err != nil {
			return err
		}
	}
	return bw.Flush()
}
