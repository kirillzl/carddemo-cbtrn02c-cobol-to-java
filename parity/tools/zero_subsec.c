/* LD_PRELOAD shim for the parity harness only: GnuCOBOL honours COB_CURRENT_DATE
 * for date/time-of-day but still takes the hundredths of a second from the real
 * clock. This shim zeroes the sub-second part so that FUNCTION CURRENT-DATE (and
 * therefore TRAN-PROC-TS) is fully deterministic. The COBOL program is unchanged. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/time.h>
#include <time.h>

int clock_gettime(clockid_t id, struct timespec *ts) {
    static int (*real)(clockid_t, struct timespec *);
    if (!real) real = dlsym(RTLD_NEXT, "clock_gettime");
    int rc = real(id, ts);
    if (rc == 0 && ts && (id == CLOCK_REALTIME || id == CLOCK_REALTIME_COARSE)) ts->tv_nsec = 0;
    return rc;
}

int gettimeofday(struct timeval *tv, void *tz) {
    static int (*real)(struct timeval *, void *);
    if (!real) real = dlsym(RTLD_NEXT, "gettimeofday");
    int rc = real(tv, tz);
    if (rc == 0 && tv) tv->tv_usec = 0;
    return rc;
}
