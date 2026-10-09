/*
 * altscreen_egl_fix — Preload shim for carplay-alt111-mirror-display on MHI2Q.
 *
 * Root Cause & Fix:
 * On Qualcomm Snapdragon 602A (APQ8064 / Adreno 320 GPU) running QNX 6.5,
 * Qualcomm's /proc/boot/egl14.so contains an internal bug in OpenSubDriver():
 *
 *   16408: bl strcmp(subdriver, "eglsub-egl_oem.so")
 *   16418: subs r8, r0, #0
 *   1641c: movne fp, r5
 *   16424: ldrne r8, [r5]
 *   16428: ldrne r5, [r5, r8, lsl #2]   <-- SIGSEGV in QNX (ref=015293b0 / 0142f3b0)
 *   16434: bl dlsym(r0, "eglSubDriverMain")
 *
 * In OpenSubDriver, Qualcomm's developers wrote a Bionic/Android linker handle
 * unpacker in the 'else' branch of the strcmp("eglsub-egl_oem.so") check. On QNX,
 * dlopen returns a standard link_map pointer; dereferencing [handle, r8, lsl #2]
 * crashes immediately with SIGSEGV code=1 fltno=11.
 * When the subdriver matches "eglsub-egl_oem.so", the branch skips the crashing
 * dereference and passes the clean dlopen handle directly to dlsym(), which succeeds.
 *
 * Fix:
 *  1. Intercept strcmp: When compared against "eglsub-egl_oem.so", return 0.
 *     This forces OpenSubDriver to take the clean path on QNX, passing the valid
 *     handle to dlsym("eglSubDriverMain") and loading eglsub-screen.so cleanly.
 *  2. Ensure QNX Screen context and managed window buffers exist for displayable 3.
 *  3. Set IPL_CONFIG_DIR=/etc/eso/production for graphics configuration.
 *  4. Provide full diagnostics for EGL initialization and surface creation.
 */

#include <screen/screen.h>
#include <EGL/egl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <setjmp.h>
#include <unistd.h>

#ifndef SCREEN_PROPERTY_TRANSPARENCY
#define SCREEN_PROPERTY_TRANSPARENCY     17
#endif
#ifndef SCREEN_PROPERTY_MANAGER_STRING
#define SCREEN_PROPERTY_MANAGER_STRING   152
#endif
#ifndef SCREEN_TRANSPARENCY_SOURCE_OVER
#define SCREEN_TRANSPARENCY_SOURCE_OVER  2
#endif

#define CS_MANAGE_GROUP    "How are you gentlemen?"
#define CS_MANAGER_STRING  "All your base are belong to us!"

static screen_context_t s_screen_ctx = NULL;
static screen_window_t  s_screen_win = NULL;
static sigjmp_buf       s_egl_jmp;
static volatile sig_atomic_t s_in_guarded_call = 0;

/* Intercept strcmp to bypass Qualcomm egl14.so OpenSubDriver bug */
int strcmp(const char *s1, const char *s2) {
    typedef int (*pfn_strcmp)(const char*, const char*);
    static pfn_strcmp real_strcmp = NULL;
    if (!real_strcmp) {
        real_strcmp = (pfn_strcmp)dlsym(RTLD_NEXT, "strcmp");
    }

    if (real_strcmp(s2, "eglsub-egl_oem.so") == 0 || real_strcmp(s1, "eglsub-egl_oem.so") == 0) {
        fprintf(stderr, "altscreen_egl_fix: bypassing Qualcomm OpenSubDriver bug for s1='%s' s2='%s'\n",
                s1, s2);
        return 0; /* Match! Skips the broken Android linker dereference */
    }

    return real_strcmp(s1, s2);
}

/* Intercept dlopen so Qualcomm's egliLoadLibrary short-name searches succeed.
 * egl14.so calls dlopen("libGLESv2", ...) and dlopen("libGLESv1", ...) without
 * a path or .so.1 suffix. On QNX the loader won't find these by short name
 * if LD_LIBRARY_PATH doesn't contain the right directory, so we redirect to the
 * full versioned soname which the linker resolves via the standard search. */
void *dlopen(const char *path, int mode) {
    typedef void *(*pfn_dlopen)(const char *, int);
    static pfn_dlopen real_dlopen = NULL;
    if (!real_dlopen) {
        real_dlopen = (pfn_dlopen)dlsym(RTLD_NEXT, "dlopen");
    }

    if (path) {
        if (strcmp(path, "libGLESv2") == 0 || strcmp(path, "libGLESv2.so") == 0) {
            fprintf(stderr, "altscreen_egl_fix: dlopen redirect '%s' -> 'libGLESv2.so.1'\n", path);
            void *h = real_dlopen("libGLESv2.so.1", mode | RTLD_GLOBAL);
            if (!h) h = real_dlopen("/mnt/app/eso/lib/libGLESv2.so.1", mode | RTLD_GLOBAL);
            if (!h) h = real_dlopen("/proc/boot/libGLESv2.so.1", mode | RTLD_GLOBAL);
            if (h) return h;
            fprintf(stderr, "altscreen_egl_fix: dlopen libGLESv2.so.1 failed, trying original\n");
        } else if (strcmp(path, "libGLESv1") == 0 || strcmp(path, "libGLESv1_CM") == 0
                   || strcmp(path, "libGLESv1.so") == 0) {
            fprintf(stderr, "altscreen_egl_fix: dlopen redirect '%s' -> 'libGLESv1_CM.so.1'\n", path);
            void *h = real_dlopen("libGLESv1_CM.so.1", mode | RTLD_GLOBAL);
            if (!h) h = real_dlopen("/mnt/app/eso/lib/libGLESv1_CM.so.1", mode | RTLD_GLOBAL);
            if (h) return h;
            /* libGLESv1 is optional for GLES2-only context; fall through */
        }
    }
    return real_dlopen(path, mode);
}

/* Pre-load libGLESv2.so.1 with RTLD_GLOBAL before eglCreateContext so
 * Qualcomm's egliLoadLibrary finds the already-resident handle. */
EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_ctx, const EGLint *attrib_list) {
    typedef EGLContext (*pfn_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
    static pfn_eglCreateContext real_fn = NULL;
    if (!real_fn) real_fn = (pfn_eglCreateContext)dlsym(RTLD_NEXT, "eglCreateContext");

    /* Ensure the GL runtime is resident so egliLoadLibrary finds it */
    static int gles_preloaded = 0;
    if (!gles_preloaded) {
        gles_preloaded = 1;
        typedef void *(*pfn_dlopen)(const char *, int);
        pfn_dlopen real_dlopen = (pfn_dlopen)dlsym(RTLD_NEXT, "dlopen");
        if (real_dlopen) {
            void *h = real_dlopen("libGLESv2.so.1", RTLD_GLOBAL | RTLD_LAZY);
            if (!h) h = real_dlopen("/mnt/app/eso/lib/libGLESv2.so.1", RTLD_GLOBAL | RTLD_LAZY);
            if (!h) h = real_dlopen("/proc/boot/libGLESv2.so.1", RTLD_GLOBAL | RTLD_LAZY);
            fprintf(stderr, "altscreen_egl_fix: libGLESv2.so.1 preload handle=%p\n", h);
        }
    }

    fprintf(stderr, "altscreen_egl_fix: eglCreateContext entered dpy=%p config=%p share=%p\n",
            (void *)dpy, (void *)config, (void *)share_ctx);

    if (!real_fn) {
        fprintf(stderr, "altscreen_egl_fix: dlsym eglCreateContext failed\n");
        return EGL_NO_CONTEXT;
    }

    EGLContext ctx = EGL_NO_CONTEXT;
    s_in_guarded_call = 1;
    if (sigsetjmp(s_egl_jmp, 1) == 0) {
        ctx = real_fn(dpy, config, share_ctx, attrib_list);
    } else {
        fprintf(stderr, "altscreen_egl_fix: real eglCreateContext crashed with SIGSEGV!\n");
        ctx = EGL_NO_CONTEXT;
    }
    s_in_guarded_call = 0;

    fprintf(stderr, "altscreen_egl_fix: eglCreateContext returning ctx=%p (err=0x%x)\n",
            (void *)ctx, (unsigned int)eglGetError());
    return ctx;
}


static void egl_segv_handler(int sig, siginfo_t *info, void *ucontext) {
    if (s_in_guarded_call) {
        fprintf(stderr, "altscreen_egl_fix: caught SIGSEGV during EGL call (addr=%p)! Recovering via siglongjmp...\n",
                info ? info->si_addr : NULL);
        s_in_guarded_call = 0;
        siglongjmp(s_egl_jmp, 1);
    }
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_signal_guard(void) {
    static int s_installed = 0;
    if (s_installed) return;
    s_installed = 1;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = egl_segv_handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
}

static void ensure_screen_and_window(void) {
    if (s_screen_ctx && s_screen_win) return;

    if (!s_screen_ctx) {
        int rc = screen_create_context(&s_screen_ctx, SCREEN_APPLICATION_CONTEXT);
        fprintf(stderr, "altscreen_egl_fix: screen_create_context rc=%d ctx=%p errno=%d\n",
                rc, (void*)s_screen_ctx, errno);
        if (rc != 0 || !s_screen_ctx) return;
    }

    if (!s_screen_win) {
        int rc = screen_create_window(&s_screen_win, s_screen_ctx);
        if (rc != 0) {
            fprintf(stderr, "altscreen_egl_fix: screen_create_window failed rc=%d errno=%d\n", rc, errno);
            return;
        }

        const char *id_str = "3"; /* Displayable 3 */
        int visible = 1;
        int format = 8; /* SCREEN_FORMAT_RGBA8888 */
        int usage = 0x20; /* SCREEN_USAGE_OPENGL_ES2 */
        int size[2] = {1440, 455};

        screen_set_window_property_cv(s_screen_win, SCREEN_PROPERTY_ID_STRING, strlen(id_str), id_str);
        screen_set_window_property_iv(s_screen_win, SCREEN_PROPERTY_VISIBLE, &visible);
        screen_set_window_property_iv(s_screen_win, SCREEN_PROPERTY_FORMAT, &format);
        screen_set_window_property_iv(s_screen_win, SCREEN_PROPERTY_USAGE, &usage);
        screen_set_window_property_iv(s_screen_win, SCREEN_PROPERTY_SIZE, size);
        screen_set_window_property_iv(s_screen_win, SCREEN_PROPERTY_BUFFER_SIZE, size);

        rc = screen_manage_window(s_screen_win, CS_MANAGE_GROUP);
        fprintf(stderr, "altscreen_egl_fix: screen_manage_window rc=%d errno=%d\n", rc, errno);

        rc = screen_create_window_buffers(s_screen_win, 2);
        fprintf(stderr, "altscreen_egl_fix: screen_create_window_buffers rc=%d nbuf=2 errno=%d win=%p\n",
                rc, errno, (void*)s_screen_win);
    }
}

__attribute__((constructor))
static void altscreen_egl_fix_ctor(void) {
    setvbuf(stderr, NULL, _IOLBF, 0);
    fprintf(stderr, "altscreen_egl_fix: ctor init\n");
    if (!getenv("IPL_CONFIG_DIR")) {
        putenv("IPL_CONFIG_DIR=/etc/eso/production");
    }
    install_signal_guard();
    ensure_screen_and_window();
}

EGLDisplay eglGetDisplay(EGLNativeDisplayType display_id) {
    install_signal_guard();
    ensure_screen_and_window();

    fprintf(stderr, "altscreen_egl_fix: eglGetDisplay entered, display_id=%p (ctx=%p, win=%p)\n",
            (void*)display_id, (void*)s_screen_ctx, (void*)s_screen_win);

    typedef EGLDisplay (*pfn_eglGetDisplay)(EGLNativeDisplayType);
    static pfn_eglGetDisplay real_fn = NULL;
    if (!real_fn) {
        real_fn = (pfn_eglGetDisplay)dlsym(RTLD_NEXT, "eglGetDisplay");
    }

    if (!real_fn) {
        fprintf(stderr, "altscreen_egl_fix: dlsym eglGetDisplay failed\n");
        return EGL_NO_DISPLAY;
    }

    EGLDisplay disp = EGL_NO_DISPLAY;

    s_in_guarded_call = 1;
    if (sigsetjmp(s_egl_jmp, 1) == 0) {
        disp = real_fn(display_id);
    } else {
        fprintf(stderr, "altscreen_egl_fix: real_fn(%p) crashed with SIGSEGV!\n", (void*)display_id);
        disp = EGL_NO_DISPLAY;
    }
    s_in_guarded_call = 0;

    if (disp == EGL_NO_DISPLAY && s_screen_ctx != NULL) {
        fprintf(stderr, "altscreen_egl_fix: trying fallback real_fn((EGLNativeDisplayType)s_screen_ctx=%p)\n",
                (void*)s_screen_ctx);
        s_in_guarded_call = 1;
        if (sigsetjmp(s_egl_jmp, 1) == 0) {
            disp = real_fn((EGLNativeDisplayType)s_screen_ctx);
        } else {
            fprintf(stderr, "altscreen_egl_fix: fallback real_fn(s_screen_ctx) also crashed!\n");
            disp = EGL_NO_DISPLAY;
        }
        s_in_guarded_call = 0;
    }

    fprintf(stderr, "altscreen_egl_fix: eglGetDisplay returning dpy=%p\n", (void*)disp);
    return disp;
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor) {
    install_signal_guard();
    ensure_screen_and_window();

    fprintf(stderr, "altscreen_egl_fix: eglInitialize entered, dpy=%p\n", (void*)dpy);

    typedef EGLBoolean (*pfn_eglInitialize)(EGLDisplay, EGLint*, EGLint*);
    static pfn_eglInitialize real_fn = NULL;
    if (!real_fn) {
        real_fn = (pfn_eglInitialize)dlsym(RTLD_NEXT, "eglInitialize");
    }

    if (!real_fn) {
        fprintf(stderr, "altscreen_egl_fix: dlsym eglInitialize failed\n");
        return EGL_FALSE;
    }

    EGLBoolean res = EGL_FALSE;
    s_in_guarded_call = 1;
    if (sigsetjmp(s_egl_jmp, 1) == 0) {
        res = real_fn(dpy, major, minor);
    } else {
        fprintf(stderr, "altscreen_egl_fix: real eglInitialize crashed with SIGSEGV!\n");
        res = EGL_FALSE;
    }
    s_in_guarded_call = 0;

    int maj = major ? *major : -1;
    int min = minor ? *minor : -1;
    fprintf(stderr, "altscreen_egl_fix: eglInitialize returning res=%d major=%d minor=%d (err=0x%x)\n",
            res, maj, min, (unsigned int)eglGetError());

    return res;
}

EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                                  EGLNativeWindowType win, const EGLint *attrib_list) {
    install_signal_guard();
    fprintf(stderr, "altscreen_egl_fix: eglCreateWindowSurface entered dpy=%p win=%p (our_win=%p)\n",
            (void*)dpy, (void*)win, (void*)s_screen_win);

    typedef EGLSurface (*pfn_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*);
    static pfn_eglCreateWindowSurface real_fn = NULL;
    if (!real_fn) real_fn = (pfn_eglCreateWindowSurface)dlsym(RTLD_NEXT, "eglCreateWindowSurface");

    if (!real_fn) {
        fprintf(stderr, "altscreen_egl_fix: dlsym eglCreateWindowSurface failed\n");
        return EGL_NO_SURFACE;
    }

    EGLNativeWindowType target_win = win;
    if (!target_win && s_screen_win) {
        fprintf(stderr, "altscreen_egl_fix: win is NULL, substituting s_screen_win=%p\n", (void*)s_screen_win);
        target_win = (EGLNativeWindowType)s_screen_win;
    }

    EGLSurface surf = EGL_NO_SURFACE;
    s_in_guarded_call = 1;
    if (sigsetjmp(s_egl_jmp, 1) == 0) {
        surf = real_fn(dpy, config, target_win, attrib_list);
    } else {
        fprintf(stderr, "altscreen_egl_fix: real eglCreateWindowSurface crashed!\n");
        surf = EGL_NO_SURFACE;
    }
    s_in_guarded_call = 0;

    fprintf(stderr, "altscreen_egl_fix: eglCreateWindowSurface returning surf=%p (err=0x%x)\n",
            (void*)surf, (unsigned int)eglGetError());
    return surf;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    install_signal_guard();
    fprintf(stderr, "altscreen_egl_fix: eglMakeCurrent entered dpy=%p draw=%p read=%p ctx=%p\n",
            (void*)dpy, (void*)draw, (void*)read, (void*)ctx);

    typedef EGLBoolean (*pfn_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
    static pfn_eglMakeCurrent real_fn = NULL;
    if (!real_fn) real_fn = (pfn_eglMakeCurrent)dlsym(RTLD_NEXT, "eglMakeCurrent");

    if (!real_fn) {
        fprintf(stderr, "altscreen_egl_fix: dlsym eglMakeCurrent failed\n");
        return EGL_FALSE;
    }

    EGLBoolean res = EGL_FALSE;
    s_in_guarded_call = 1;
    if (sigsetjmp(s_egl_jmp, 1) == 0) {
        res = real_fn(dpy, draw, read, ctx);
    } else {
        fprintf(stderr, "altscreen_egl_fix: real eglMakeCurrent crashed!\n");
        res = EGL_FALSE;
    }
    s_in_guarded_call = 0;

    fprintf(stderr, "altscreen_egl_fix: eglMakeCurrent returning res=%d (err=0x%x)\n",
            res, (unsigned int)eglGetError());
    return res;
}
