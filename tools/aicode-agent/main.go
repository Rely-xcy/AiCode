package main

import (
	"bufio"
	"errors"
	"flag"
	"io"
	"os"
	"os/exec"
	"time"
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}

// run 拆出来便于测试：三根标准流都从参数进来，测试就能塞内存流跑完整主循环。
func run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	flags := flag.NewFlagSet("aicode-agent", flag.ContinueOnError)
	flags.SetOutput(stderr)
	root := flags.String("root", "", "允许访问的根目录；所有请求路径都必须落在其内")
	if err := flags.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		return 2
	}

	log := newLogger(stderr)
	if *root == "" {
		log.fatalf("-root is required")
		flags.Usage()
		return 2
	}
	// 根目录不可用时直接退出：没有边界可守的 agent 不应该接受任何请求。
	sb, err := newSandbox(*root)
	if err != nil {
		log.fatalf("root unavailable: %v", err)
		return 2
	}
	ag := &agent{sb: sb, log: log, hasRg: hasRipgrep()}
	log.start(*root, sb.root, ag.hasRg)
	return serve(ag, stdin, stdout)
}

// serve 是主循环：读一帧、执行、写一帧，严格串行。
// 不做并发是刻意的——并发由 Android 端起多个 agent 进程实例获得，
// 进程内再并行会让 stderr 日志顺序与实际执行顺序脱钩，排查时无法复现。
func serve(a *agent, stdin io.Reader, stdout io.Writer) int {
	fr := newFrameReader(stdin)
	bw := bufio.NewWriterSize(stdout, 64<<10)
	for {
		req, body, err := fr.next()
		if err != nil {
			if errors.Is(err, io.EOF) {
				return 0
			}
			var fe *frameError
			if errors.As(err, &fe) {
				// 帧坏了也回一帧错误：客户端按 id 等响应，静默退出会让它一直卡到超时。
				a.log.frameFailure(fe, 0)
				resp := &response{ID: fe.id, Ok: false, Err: fe.code, Msg: clampMsg(fe.msg)}
				if werr := writeFrame(bw, resp, nil); werr != nil {
					a.log.fatalf("write stdout: %v", werr)
					return 1
				}
				a.log.fatalf("invalid frame: %s", fe.msg)
				return 2
			}
			a.log.fatalf("read stdin: %v", err)
			return 1
		}

		began := time.Now()
		rep := a.handle(req, body)
		if err := writeFrame(bw, rep.resp, rep.body); err != nil {
			a.log.fatalf("write stdout: %v", err)
			return 1
		}
		a.log.frame(req, len(body), rep.resp, time.Since(began))
	}
}

// hasRipgrep 只上报能力位，不真的调用 rg：grep 全程走标准库实现，
// 避免为一个可选加速引入子进程与转义风险。
func hasRipgrep() bool {
	_, err := exec.LookPath("rg")
	return err == nil
}
