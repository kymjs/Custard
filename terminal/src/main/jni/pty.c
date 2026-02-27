#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdio.h>
#include <android/log.h>
#include <termios.h>

#ifdef __linux__
#include <pty.h>
#elif __APPLE__
#include <util.h>
#endif

#define TAG "PtyJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(JNIEnv *env, jobject thiz,
                                                                          jobjectArray cmdarray,
                                                                          jobjectArray envarray,
                                                                          jstring workingDir) {
    int master_fd;
    pid_t pid;

    const char *cwd_jni = (*env)->GetStringUTFChars(env, workingDir, 0);
    char *cwd = strdup(cwd_jni);
    (*env)->ReleaseStringUTFChars(env, workingDir, cwd_jni);

    int env_len = (*env)->GetArrayLength(env, envarray);
    char **envp = (char **) malloc(sizeof(char *) * (env_len + 1));
    for (int i = 0; i < env_len; i++) {
        jstring j_env_str = (jstring) (*env)->GetObjectArrayElement(env, envarray, i);
        const char *env_str = (*env)->GetStringUTFChars(env, j_env_str, 0);
        envp[i] = strdup(env_str);
        (*env)->ReleaseStringUTFChars(env, j_env_str, env_str);
        (*env)->DeleteLocalRef(env, j_env_str);
    }
    envp[env_len] = NULL;

    int cmd_len = (*env)->GetArrayLength(env, cmdarray);
    char **argv = (char **) malloc(sizeof(char *) * (cmd_len + 1));
    for (int i = 0; i < cmd_len; i++) {
        jstring j_cmd_str = (jstring) (*env)->GetObjectArrayElement(env, cmdarray, i);
        const char *cmd_str = (*env)->GetStringUTFChars(env, j_cmd_str, 0);
        argv[i] = strdup(cmd_str);
        (*env)->ReleaseStringUTFChars(env, j_cmd_str, cmd_str);
        (*env)->DeleteLocalRef(env, j_cmd_str);
    }
    argv[cmd_len] = NULL;

    struct termios tt;
    memset(&tt, 0, sizeof(tt));
    tt.c_iflag = ICRNL | IXON | IXANY;
    tt.c_oflag = OPOST | ONLCR;
    tt.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHONL | IEXTEN;
    tt.c_cflag = CS8 | CREAD;
    tt.c_cc[VINTR]    = 'C' - '@';
    tt.c_cc[VQUIT]    = '\\' - '@';
    tt.c_cc[VERASE]   = 0x7f; // DEL
    tt.c_cc[VKILL]    = 'U' - '@';
    tt.c_cc[VEOF]     = 'D' - '@';
    tt.c_cc[VSTOP]    = 'S' - '@';
    tt.c_cc[VSUSP]    = 'Z' - '@';
    tt.c_cc[VSTART]   = 'Q' - '@';
    tt.c_cc[VMIN]     = 1;
    tt.c_cc[VTIME]    = 0;

    struct winsize ws;
    ws.ws_row = 60;
    ws.ws_col = 40;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    pid = forkpty(&master_fd, NULL, &tt, &ws);

    if (pid < 0) {
        LOGE("forkpty failed");
        fprintf(stderr, "forkpty failed: %s\n", strerror(errno));

        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return NULL;
    }

    if (pid == 0) { // Child process
        if (chdir(cwd) != 0) {
            fprintf(stderr, "chdir to %s failed: %s\n", cwd, strerror(errno));
            _exit(1);
        }

        execve(argv[0], argv, envp);

        // execve should not return
        fprintf(stderr, "execve(%s) failed: %s\n", argv[0], strerror(errno));
        _exit(1);
    } else { // Parent process
        jintArray result = (*env)->NewIntArray(env, 2);
        if (result == NULL) {
            free(cwd);
            for (int i = 0; i < cmd_len; i++) free(argv[i]);
            free(argv);
            for (int i = 0; i < env_len; i++) free(envp[i]);
            free(envp);
            return NULL; // out of memory error thrown
        }
        jint fill[2];
        fill[0] = pid;
        fill[1] = master_fd;
        (*env)->SetIntArrayRegion(env, result, 0, 2, fill);

        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return result;
    }
}

JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return -1;
}

/**
 * 获取 PTY 的终端属性
 * 返回值：
 *  bit 0: ICANON - canonical mode (line-buffered input)
 *  bit 1: ECHO - echo input characters
 *  bit 2: ISIG - generate signals for special characters
 *  bit 3: IEXTEN - enable extended input processing
 */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getTerminalFlags(JNIEnv *env, jobject thiz, jint fd) {
    struct termios tt;
    if (tcgetattr(fd, &tt) != 0) {
        LOGE("tcgetattr failed for fd %d", fd);
        return -1;
    }
    
    jint flags = 0;
    if (tt.c_lflag & ICANON) flags |= 0x01;
    if (tt.c_lflag & ECHO)   flags |= 0x02;
    if (tt.c_lflag & ISIG)   flags |= 0x04;
    if (tt.c_lflag & IEXTEN) flags |= 0x08;
    
    LOGD("Terminal flags for fd %d: ICANON=%d, ECHO=%d, ISIG=%d, IEXTEN=%d",
         fd,
         (flags & 0x01) != 0,
         (flags & 0x02) != 0,
         (flags & 0x04) != 0,
         (flags & 0x08) != 0);
    
    return flags;
}

/**
 * 检查 PTY 是否有未读数据（用于检测程序是否在等待输入）
 * 返回值：可读字节数，-1 表示错误
 */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_getAvailableBytes(JNIEnv *env, jobject thiz, jint fd) {
    int available = 0;
    if (ioctl(fd, FIONREAD, &available) != 0) {
        LOGE("ioctl FIONREAD failed for fd %d", fd);
        return -1;
    }
    return available;
}

/**
 * 设置 PTY 窗口大小
 * 参数：
 *   fd - PTY master 文件描述符
 *   rows - 行数
 *   cols - 列数
 * 返回值：0 表示成功，-1 表示失败
 */
JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_setPtyWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGE("ioctl TIOCSWINSZ failed for fd %d: rows=%d, cols=%d", fd, rows, cols);
        return -1;
    }
    
    LOGD("PTY window size set to %dx%d for fd %d", rows, cols, fd);
    return 0;
} 