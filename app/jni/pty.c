/*
 * Minimal PTY bridge for a real terminal inside the app.
 * forkpty() + execve(); the Java side reads/writes the master fd.
 */
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

static char **to_vec(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) {
        char **empty = (char **) calloc(1, sizeof(char *));
        return empty;
    }
    jsize n = (*env)->GetArrayLength(env, arr);
    char **v = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (v == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (s == NULL) {
            continue;
        }
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        v[i] = strdup(c == NULL ? "" : c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    v[n] = NULL;
    return v;
}

static void free_vec(char **v) {
    if (v == NULL) {
        return;
    }
    for (int i = 0; v[i] != NULL; i++) {
        free(v[i]);
    }
    free(v);
}

JNIEXPORT jintArray JNICALL
Java_com_attc_opencode_Pty_nativeOpen(JNIEnv *env, jclass clazz,
                                      jobjectArray argv, jobjectArray envp,
                                      jstring cwd, jint rows, jint cols) {
    char **av = to_vec(env, argv);
    char **ev = to_vec(env, envp);
    if (av == NULL || ev == NULL || av[0] == NULL) {
        free_vec(av);
        free_vec(ev);
        return NULL;
    }
    const char *dir = cwd == NULL ? NULL : (*env)->GetStringUTFChars(env, cwd, NULL);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        if (dir != NULL) {
            (*env)->ReleaseStringUTFChars(env, cwd, dir);
        }
        free_vec(av);
        free_vec(ev);
        return NULL;
    }
    if (pid == 0) {
        if (dir != NULL && dir[0] != '\0') {
            chdir(dir);
        }
        execve(av[0], av, ev);
        /* Report the real reason on the terminal instead of a bare exit code. */
        {
            char msg[600];
            int n = snprintf(msg, sizeof(msg),
                             "\r\n[attc] could not start %s: %s\r\n",
                             av[0], strerror(errno));
            if (n > 0) {
                ssize_t ignored = write(STDERR_FILENO, msg, (size_t) n);
                (void) ignored;
            }
        }
        _exit(127);
    }
    if (dir != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, dir);
    }
    free_vec(av);
    free_vec(ev);

    jintArray out = (*env)->NewIntArray(env, 2);
    if (out == NULL) {
        close(master);
        return NULL;
    }
    jint pair[2];
    pair[0] = (jint) master;
    pair[1] = (jint) pid;
    (*env)->SetIntArrayRegion(env, out, 0, 2, pair);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_attc_opencode_Pty_nativeRead(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf) {
    jsize len = (*env)->GetArrayLength(env, buf);
    if (len <= 0) {
        return 0;
    }
    jbyte *tmp = (*env)->GetByteArrayElements(env, buf, NULL);
    if (tmp == NULL) {
        return -1;
    }
    ssize_t n;
    do {
        n = read((int) fd, tmp, (size_t) len);
    } while (n < 0 && errno == EINTR);
    (*env)->ReleaseByteArrayElements(env, buf, tmp, 0);
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_attc_opencode_Pty_nativeWrite(JNIEnv *env, jclass clazz, jint fd,
                                       jbyteArray buf, jint off, jint len) {
    if (len <= 0) {
        return 0;
    }
    jbyte *tmp = (*env)->GetByteArrayElements(env, buf, NULL);
    if (tmp == NULL) {
        return -1;
    }
    ssize_t n = write((int) fd, tmp + off, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, buf, tmp, JNI_ABORT);
    if (n < 0) {
        return -1;
    }
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_com_attc_opencode_Pty_nativeResize(JNIEnv *env, jclass clazz, jint fd,
                                        jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    return ioctl((int) fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_attc_opencode_Pty_nativeWaitPid(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) {
        return -1; /* still running */
    }
    if (r < 0) {
        return -2; /* already reaped */
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_attc_opencode_Pty_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) {
        close((int) fd);
    }
}
