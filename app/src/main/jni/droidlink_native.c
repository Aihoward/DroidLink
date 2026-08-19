#include <jni.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>

static int emit(int fd, int type, int code, int value) {
  struct input_event ev; memset(&ev, 0, sizeof(ev)); ev.type=type; ev.code=code; ev.value=value;
  return write(fd, &ev, sizeof(ev)) == sizeof(ev);
}
static int bit(int fd, unsigned long request, int value) { return ioctl(fd, request, value) >= 0; }

JNIEXPORT jlong JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeCreate(JNIEnv* env, jclass cls, jstring jname) {
  int fd=open("/dev/uinput", O_WRONLY|O_NONBLOCK); if(fd<0) return -errno;
  int keys[]={BTN_SOUTH,BTN_EAST,BTN_NORTH,BTN_WEST,BTN_TL,BTN_TR,BTN_TL2,BTN_TR2,BTN_SELECT,BTN_START,BTN_MODE,BTN_THUMBL,BTN_THUMBR,BTN_DPAD_UP,BTN_DPAD_DOWN,BTN_DPAD_LEFT,BTN_DPAD_RIGHT};
  int axes[]={ABS_X,ABS_Y,ABS_Z,ABS_RX,ABS_RY,ABS_RZ,ABS_HAT0X,ABS_HAT0Y};
  if(!bit(fd,UI_SET_EVBIT,EV_KEY)||!bit(fd,UI_SET_EVBIT,EV_ABS)) goto fail;
  for(unsigned i=0;i<sizeof(keys)/sizeof(keys[0]);i++) if(!bit(fd,UI_SET_KEYBIT,keys[i])) goto fail;
  for(unsigned i=0;i<sizeof(axes)/sizeof(axes[0]);i++) if(!bit(fd,UI_SET_ABSBIT,axes[i])) goto fail;
  struct uinput_user_dev dev; memset(&dev,0,sizeof(dev));
  const char* name=(*env)->GetStringUTFChars(env,jname,0); strncpy(dev.name,name,UINPUT_MAX_NAME_SIZE-1); (*env)->ReleaseStringUTFChars(env,jname,name);
  dev.id.bustype=BUS_USB; dev.id.vendor=0x045e; dev.id.product=0x028e; dev.id.version=0x0114;
  dev.absmin[ABS_X]=dev.absmin[ABS_Y]=dev.absmin[ABS_RX]=dev.absmin[ABS_RY]=-32768;
  dev.absmax[ABS_X]=dev.absmax[ABS_Y]=dev.absmax[ABS_RX]=dev.absmax[ABS_RY]=32767;
  /* Match the wired Xbox 360 evdev ranges used by the advertised VID/PID. */
  dev.absmax[ABS_Z]=dev.absmax[ABS_RZ]=255;
  dev.absflat[ABS_X]=dev.absflat[ABS_Y]=dev.absflat[ABS_RX]=dev.absflat[ABS_RY]=4096;
  dev.absmin[ABS_HAT0X]=dev.absmin[ABS_HAT0Y]=-1; dev.absmax[ABS_HAT0X]=dev.absmax[ABS_HAT0Y]=1;
  if(write(fd,&dev,sizeof(dev))!=sizeof(dev)||ioctl(fd,UI_DEV_CREATE)<0) goto fail;
  return fd;
fail: { int e=errno; close(fd); return -e; }
}
JNIEXPORT jboolean JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeKey(JNIEnv* e,jclass c,jlong h,jint code,jboolean down){int fd=(int)h;return emit(fd,EV_KEY,code,down?1:0)&&emit(fd,EV_SYN,SYN_REPORT,0);}
static int sv(float v){if(v>1)v=1;if(v<-1)v=-1;return(int)(v*32767.f);} static int tv(float v){if(v>1)v=1;if(v<0)v=0;return(int)(v*255.f);}
JNIEXPORT jboolean JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeAxes(JNIEnv* e,jclass c,jlong h,jfloat lx,jfloat ly,jfloat rx,jfloat ry,jfloat lt,jfloat rt,jfloat dx,jfloat dy){int f=(int)h;int ok=emit(f,EV_ABS,ABS_X,sv(lx))&&emit(f,EV_ABS,ABS_Y,sv(ly))&&emit(f,EV_ABS,ABS_RX,sv(rx))&&emit(f,EV_ABS,ABS_RY,sv(ry))&&emit(f,EV_ABS,ABS_Z,tv(lt))&&emit(f,EV_ABS,ABS_RZ,tv(rt))&&emit(f,EV_SYN,SYN_REPORT,0);return ok;}
JNIEXPORT jboolean JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeDpad(JNIEnv* e,jclass c,jlong h,jint dx,jint dy){int f=(int)h;if(dx>1)dx=1;if(dx<-1)dx=-1;if(dy>1)dy=1;if(dy<-1)dy=-1;return emit(f,EV_ABS,ABS_HAT0X,dx)&&emit(f,EV_ABS,ABS_HAT0Y,dy)&&emit(f,EV_SYN,SYN_REPORT,0);}
JNIEXPORT jboolean JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeReset(JNIEnv* e,jclass c,jlong h){int f=(int)h;int keys[]={BTN_SOUTH,BTN_EAST,BTN_NORTH,BTN_WEST,BTN_TL,BTN_TR,BTN_TL2,BTN_TR2,BTN_SELECT,BTN_START,BTN_MODE,BTN_THUMBL,BTN_THUMBR,BTN_DPAD_UP,BTN_DPAD_DOWN,BTN_DPAD_LEFT,BTN_DPAD_RIGHT};for(unsigned i=0;i<sizeof(keys)/sizeof(keys[0]);i++)if(!emit(f,EV_KEY,keys[i],0))return 0;int axes[]={ABS_X,ABS_Y,ABS_Z,ABS_RX,ABS_RY,ABS_RZ,ABS_HAT0X,ABS_HAT0Y};for(unsigned i=0;i<sizeof(axes)/sizeof(axes[0]);i++)if(!emit(f,EV_ABS,axes[i],0))return 0;return emit(f,EV_SYN,SYN_REPORT,0);}
JNIEXPORT void JNICALL Java_com_droidlink_app_UinputVirtualGamepadBackend_nativeDestroy(JNIEnv* e,jclass c,jlong h){int fd=(int)h;if(fd>=0){ioctl(fd,UI_DEV_DESTROY);close(fd);}}

JNIEXPORT jlong JNICALL Java_com_droidlink_app_DolphinVirtualGamepadBackend_nativeCreate(JNIEnv* env, jclass cls, jstring jname) {
  int fd=open("/dev/uinput", O_WRONLY|O_NONBLOCK); if(fd<0) return -errno;
  int keys[]={BTN_SOUTH,BTN_EAST,BTN_NORTH,BTN_WEST,BTN_START,BTN_TR,BTN_TL2,BTN_TR2};
  int axes[]={ABS_X,ABS_Y,ABS_RX,ABS_RY,ABS_Z,ABS_RZ,ABS_HAT0X,ABS_HAT0Y};
  if(!bit(fd,UI_SET_EVBIT,EV_KEY)||!bit(fd,UI_SET_EVBIT,EV_ABS)) goto fail;
  for(unsigned i=0;i<sizeof(keys)/sizeof(keys[0]);i++) if(!bit(fd,UI_SET_KEYBIT,keys[i])) goto fail;
  for(unsigned i=0;i<sizeof(axes)/sizeof(axes[0]);i++) if(!bit(fd,UI_SET_ABSBIT,axes[i])) goto fail;
  struct uinput_user_dev dev; memset(&dev,0,sizeof(dev));
  const char* name=(*env)->GetStringUTFChars(env,jname,0); strncpy(dev.name,name,UINPUT_MAX_NAME_SIZE-1); (*env)->ReleaseStringUTFChars(env,jname,name);
  dev.id.bustype=BUS_USB; dev.id.vendor=0x045e; dev.id.product=0x028e; dev.id.version=0x0114;
  dev.absmin[ABS_X]=dev.absmin[ABS_Y]=dev.absmin[ABS_RX]=dev.absmin[ABS_RY]=-32768;
  dev.absmax[ABS_X]=dev.absmax[ABS_Y]=dev.absmax[ABS_RX]=dev.absmax[ABS_RY]=32767;
  dev.absmax[ABS_Z]=dev.absmax[ABS_RZ]=255;
  dev.absflat[ABS_X]=dev.absflat[ABS_Y]=dev.absflat[ABS_RX]=dev.absflat[ABS_RY]=4096;
  dev.absmin[ABS_HAT0X]=dev.absmin[ABS_HAT0Y]=-1; dev.absmax[ABS_HAT0X]=dev.absmax[ABS_HAT0Y]=1;
  if(write(fd,&dev,sizeof(dev))!=sizeof(dev)||ioctl(fd,UI_DEV_CREATE)<0) goto fail;
  return fd;
fail: { int e=errno; close(fd); return -e; }
}

JNIEXPORT jboolean JNICALL Java_com_droidlink_app_DolphinVirtualGamepadBackend_nativeUpdateState(
    JNIEnv* e,jclass c,jlong h,
    jboolean a,jboolean b,jboolean x,jboolean y,jboolean start,jboolean z,
    jboolean digital_l,jboolean digital_r,jint dpad_x,jint dpad_y,
    jfloat main_x,jfloat main_y,jfloat c_x,jfloat c_y,jfloat analog_l,jfloat analog_r) {
  int fd=(int)h;
  if(dpad_x>1)dpad_x=1;if(dpad_x<-1)dpad_x=-1;
  if(dpad_y>1)dpad_y=1;if(dpad_y<-1)dpad_y=-1;
  int ok=emit(fd,EV_KEY,BTN_SOUTH,a?1:0)&&emit(fd,EV_KEY,BTN_EAST,b?1:0)&&
      emit(fd,EV_KEY,BTN_NORTH,x?1:0)&&emit(fd,EV_KEY,BTN_WEST,y?1:0)&&
      emit(fd,EV_KEY,BTN_START,start?1:0)&&emit(fd,EV_KEY,BTN_TR,z?1:0)&&
      emit(fd,EV_KEY,BTN_TL2,digital_l?1:0)&&emit(fd,EV_KEY,BTN_TR2,digital_r?1:0)&&
      emit(fd,EV_ABS,ABS_HAT0X,dpad_x)&&emit(fd,EV_ABS,ABS_HAT0Y,dpad_y)&&
      emit(fd,EV_ABS,ABS_X,sv(main_x))&&emit(fd,EV_ABS,ABS_Y,sv(main_y))&&
      emit(fd,EV_ABS,ABS_RX,sv(c_x))&&emit(fd,EV_ABS,ABS_RY,sv(c_y))&&
      emit(fd,EV_ABS,ABS_Z,tv(analog_l))&&emit(fd,EV_ABS,ABS_RZ,tv(analog_r))&&
      emit(fd,EV_SYN,SYN_REPORT,0);
  return ok;
}

JNIEXPORT void JNICALL Java_com_droidlink_app_DolphinVirtualGamepadBackend_nativeDestroy(JNIEnv* e,jclass c,jlong h){int fd=(int)h;if(fd>=0){ioctl(fd,UI_DEV_DESTROY);close(fd);}}
