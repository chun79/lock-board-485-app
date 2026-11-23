/*
 * Minimal POSIX serial port access for on-board UARTs (/dev/ttyS*, /dev/ttyUSB*, ...).
 * Raw 8N1-style termios, no software/hardware flow control, poll() based read timeouts,
 * short-write handling and optional kernel RS485 mode (TIOCSRS485) with read-back.
 */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/serial.h>

#ifndef TIOCSRS485
#define TIOCSRS485 0x542F
#endif
#ifndef TIOCGRS485
#define TIOCGRS485 0x542E
#endif
#ifndef SER_RS485_ENABLED
#define SER_RS485_ENABLED (1 << 0)
#define SER_RS485_RTS_ON_SEND (1 << 1)
#define SER_RS485_RTS_AFTER_SEND (1 << 2)
#endif

static void throw_io(JNIEnv *env, const char *what, int err) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s (errno %d)", what, strerror(err), err);
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static speed_t to_speed(int baud) {
    switch (baud) {
        case 1200: return B1200;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 921600: return B921600;
        default: return 0;
    }
}

JNIEXPORT jint JNICALL
Java_cn_tuodi_lockboard_transport_NativeSerial_nativeOpen(JNIEnv *env, jclass clazz, jstring jpath, jint baud,
                                                          jint data_bits, jint parity, jint stop_bits) {
    (void) clazz;
    speed_t speed = to_speed(baud);
    if (speed == 0) { throw_io(env, "不支持的波特率", EINVAL); return -1; }
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return -1;
    int fd = open(path, O_RDWR | O_NOCTTY | O_NONBLOCK | O_CLOEXEC);
    int open_err = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) { throw_io(env, "打开串口失败", open_err); return -1; }

    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) { int e = errno; close(fd); throw_io(env, "读取串口属性失败", e); return -1; }
    cfmakeraw(&tio);
    tio.c_cflag |= (CLOCAL | CREAD);
    tio.c_cflag &= ~CRTSCTS;
    tio.c_iflag &= ~(IXON | IXOFF | IXANY);
    tio.c_cflag &= ~CSIZE;
    switch (data_bits) {
        case 5: tio.c_cflag |= CS5; break;
        case 6: tio.c_cflag |= CS6; break;
        case 7: tio.c_cflag |= CS7; break;
        default: tio.c_cflag |= CS8; break;
    }
    tio.c_cflag &= ~(PARENB | PARODD);
    if (parity == 1) tio.c_cflag |= (PARENB | PARODD);      /* odd */
    else if (parity == 2) tio.c_cflag |= PARENB;            /* even */
    if (stop_bits == 2) tio.c_cflag |= CSTOPB; else tio.c_cflag &= ~CSTOPB;
    tio.c_cc[VMIN] = 0;
    tio.c_cc[VTIME] = 0;
    cfsetispeed(&tio, speed);
    cfsetospeed(&tio, speed);
    if (tcsetattr(fd, TCSANOW, &tio) != 0) { int e = errno; close(fd); throw_io(env, "设置串口参数失败", e); return -1; }
    tcflush(fd, TCIOFLUSH);
    return fd;
}

/* Returns a human-readable read-back of the RS485 configuration, or throws. */
JNIEXPORT jstring JNICALL
Java_cn_tuodi_lockboard_transport_NativeSerial_nativeEnableRs485(JNIEnv *env, jclass clazz, jint fd) {
    (void) clazz;
    struct serial_rs485 rs;
    memset(&rs, 0, sizeof rs);
    rs.flags = SER_RS485_ENABLED | SER_RS485_RTS_ON_SEND;
    rs.flags &= ~SER_RS485_RTS_AFTER_SEND;
    if (ioctl(fd, TIOCSRS485, &rs) != 0) { throw_io(env, "驱动不支持 TIOCSRS485", errno); return NULL; }
    struct serial_rs485 back;
    memset(&back, 0, sizeof back);
    if (ioctl(fd, TIOCGRS485, &back) != 0) { throw_io(env, "读回 RS485 设置失败", errno); return NULL; }
    char msg[160];
    snprintf(msg, sizeof msg, "RS485 已启用：flags=0x%x，RTS发送=%d，RTS发送后=%d，延时 %u/%u ms",
             back.flags, (back.flags & SER_RS485_RTS_ON_SEND) != 0, (back.flags & SER_RS485_RTS_AFTER_SEND) != 0,
             back.delay_rts_before_send, back.delay_rts_after_send);
    if ((back.flags & SER_RS485_ENABLED) == 0) { throw_io(env, "驱动未保留 RS485 使能位", EINVAL); return NULL; }
    return (*env)->NewStringUTF(env, msg);
}

/* Waits up to timeout_ms; returns bytes read, 0 on timeout; throws on error/hang-up. */
JNIEXPORT jint JNICALL
Java_cn_tuodi_lockboard_transport_NativeSerial_nativeRead(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf,
                                                          jint timeout_ms) {
    (void) clazz;
    struct pollfd p = { .fd = fd, .events = POLLIN, .revents = 0 };
    int r;
    do { r = poll(&p, 1, timeout_ms); } while (r < 0 && errno == EINTR);
    if (r < 0) { throw_io(env, "poll 失败", errno); return -1; }
    if (r == 0) return 0;
    if (p.revents & (POLLERR | POLLNVAL)) { throw_io(env, "串口错误", EIO); return -1; }
    jsize cap = (*env)->GetArrayLength(env, buf);
    jbyte tmp[512];
    if (cap > (jsize) sizeof tmp) cap = sizeof tmp;
    ssize_t n;
    do { n = read(fd, tmp, (size_t) cap); } while (n < 0 && errno == EINTR);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        throw_io(env, "读取失败", errno);
        return -1;
    }
    if (n == 0) {
        if (p.revents & POLLHUP) { throw_io(env, "串口已挂断", EIO); return -1; }
        return 0;
    }
    (*env)->SetByteArrayRegion(env, buf, 0, (jsize) n, tmp);
    return (jint) n;
}

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/*
 * Writes all bytes (handling short writes) and waits until the driver's output queue is empty
 * (TIOCOUTQ), all within timeout_ms. Unlike tcdrain() this cannot block forever on a stalled driver.
 */
JNIEXPORT void JNICALL
Java_cn_tuodi_lockboard_transport_NativeSerial_nativeWrite(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf,
                                                           jint timeout_ms) {
    (void) clazz;
    long long deadline = now_ms() + (timeout_ms > 0 ? timeout_ms : 1000);
    jsize len = (*env)->GetArrayLength(env, buf);
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!data) return;
    jsize off = 0;
    int err = 0;
    while (off < len) {
        ssize_t n = write(fd, data + off, (size_t) (len - off));
        if (n > 0) { off += (jsize) n; continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            long long left = deadline - now_ms();
            if (left <= 0) { err = ETIMEDOUT; break; }
            struct pollfd p = { .fd = fd, .events = POLLOUT, .revents = 0 };
            int r = poll(&p, 1, (int) left);
            if (r > 0) continue;
            err = r == 0 ? ETIMEDOUT : errno;
            break;
        }
        err = n < 0 ? errno : EIO;
        break;
    }
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    if (err) { throw_io(env, "写入失败", err); return; }
    /* Wait for the bytes to leave the UART so the next read window starts after transmission. */
    for (;;) {
        int queued = 0;
        if (ioctl(fd, TIOCOUTQ, &queued) != 0) {
            if (errno == ENOTTY || errno == EINVAL || errno == EOPNOTSUPP) break; /* driver without TIOCOUTQ */
            if (errno == EINTR) continue;
            throw_io(env, "查询发送队列失败", errno);
            return;
        }
        if (queued <= 0) break;
        if (now_ms() >= deadline) { throw_io(env, "发送超时（驱动输出队列未清空）", ETIMEDOUT); return; }
        usleep(1000);
    }
}

JNIEXPORT void JNICALL
Java_cn_tuodi_lockboard_transport_NativeSerial_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close(fd);
}
