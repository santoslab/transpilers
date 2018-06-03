#include <all.h>
#include <sys/msg.h>

#ifdef _WIN32
#include <Windows.h>
#else
#include <unistd.h>
#endif

#define MIN_TEMP 55
#define MAX_TEMP 100

struct MessageSize {
  long mtype;
  size_t size;
};

struct Message {
  long mtype;
  union art_DataContent data;
};

static int msqid = 0;
static int temp = 0;
static int delta = 1;

Z building_control_gen_periodic_MessageQueue_create(StackFrame caller, Z msgid) {
  unsigned int permission = 0666;
  unsigned int mask = IPC_CREAT;
  msqid = msgget((key_t) msgid, (int) (permission | mask));
  return (Z) msqid;
}

Unit building_control_gen_periodic_MessageQueue_remove(StackFrame caller, Z msgid) {
  msgctl((int) msgid, IPC_RMID, NULL);
}

void building_control_gen_periodic_MessageQueue_receive(Tuple2_D0E3BB result, StackFrame caller) {
  struct MessageSize msize;
  struct Message r;
  msgrcv(msqid, &msize, sizeof(size_t), 0, 0);
  msgrcv(msqid, &r, msize.size, 0, 0);
  //printf("%s", "Received from port ");
  //Z_cprint((Z) r.mtype, T);
  //printf("%s", " data: ");
  //art_DataContent_cprint(&r.data, T);
  //printf("\n");
  result->type = TTuple2_D0E3BB;
  result->_1 = (Z) r.mtype;
  Type_assign(&result->_2, &r.data, msize.size);
}

Unit building_control_gen_periodic_MessageQueue_send(StackFrame caller, Z msgid, Z port, art_DataContent d) {
  struct MessageSize msize = { .mtype = port, .size = sizeOf((Type) d) };
  struct Message m = { .mtype = port, .data = *d };
  int msqid = msgget((key_t) msgid, 0644);
  //printf("%s", "Sending to port ");
  //Z_cprint(port, T);
  //printf("%s", " data: ");
  //art_DataContent_cprint(d, T);
  //printf("\n");
  msgsnd(msqid, &msize, sizeof(size_t), 0);
  msgsnd(msqid, &m, msize.size, 0);
}

Unit building_control_gen_periodic_Process_sleep(StackFrame caller, Z n) {
#ifdef _WIN32
  Sleep((DWORD) n);
#else
  usleep((useconds_t) n * 1000);
#endif
}

building_control_gen_periodic_BuildingControl_FanAck building_control_gen_periodic_BuildingControl_FanNative_fanCmdActuate(StackFrame caller, building_control_gen_periodic_BuildingControl_FanCmd cmd) {
  return building_control_gen_periodic_BuildingControl_FanAck_Ok;
}

void building_control_gen_periodic_BuildingControl_TempSensorNative_currentTempGet(building_control_gen_periodic_BuildingControl_Temperature result, StackFrame caller) {
  if (temp == 0) {
    temp = MIN_TEMP;
  }

  temp += delta;
  if (temp < MIN_TEMP) delta = 4;
  else if (temp > MAX_TEMP) delta = -4;

  result->degree = (F32) temp;
  result->unit = building_control_gen_periodic_BuildingControl_TempUnit_Fahrenheit;
}