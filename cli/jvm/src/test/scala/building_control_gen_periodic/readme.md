# Building Control Slang-to-C Showcase

The building control example is a simple system with three components:
(1) temperature sensor, (2) temperature controller, and (3) fan, which
together work to regulate the temperature of a building room.
That is, the sensor periodically measure the room temperature and send
the data to the controller. 
If the temperature is too high, it sends command to turn on the fan. 
Conversely, if it is too low, it turns off the fan.
The fan sends acknowledgement to the controller whether the actuation
is successful.
In this simplified example, all components are periodic.

Below is a depiction of the architecture:

```
   +----------------+             +-----------------+             +-----------------+
   |                |             |                 |   fanCmd    |                 |
   |                |             |                 >------------->                 |
   |                | currentTemp |                 |             |                 |
   |   TempSensor   >------------->   TempControl   |             |       Fan       |
   |   (periodic)   |             |   (periodic)    |   fanAck    |    (periodic)   |
   |                |             |                 <-------------<                 |
   |                |             |                 |             |                 |
   +----------------+             +--------∧--------+             +-----------------+
                                           |
                                           | setPoint

```


## Artifacts

* The AADL model is located at [src/aadl](src/aadl)

* The [AIR](http://github.com/sireum/air) JSON representation of the AADL model is located at 
  [src/aadl/.slang/BuildingControl_periodic_BuildingControlDemo_i_Instance.json](src/aadl/.slang/BuildingControl_periodic_BuildingControlDemo_i_Instance.json)

* The AADL Runtime (ART) provides a platform-independent runtime services; 
  the Slang implementation, which can be used for simulation using the Java Virtual Machine (JVM), 
  is located at [src/scala/art](src/scala/art). 
  Platform-specific services are factored out as a Slang extension -- [ArtNative.scala](src/scala/art/ArtNative.scala),
  that adopts the API described in the SAE AS5506 AADL standard. 

* The Arsit translator takes AIR and generates Slang "glue code" that uses ART services:

  * [src/scala/component](src/scala/component): provides skeleton and API for developers to code up component behavior
    by extending traits/interfaces (OOP-style): 
      
    * [TempSensor_i.scala](src/scala/component/building_control_gen_periodic/BuildingControl/TempSensor_i.scala):
      The behavior is realized in
      [TempSensor_i_Impl.scala](src/scala/component/building_control_gen_periodic/BuildingControl/TempSensor_i_Impl.scala).
      It uses a Slang extension -- `TempSensorNative`, to access a hardware sensor to measure the room temperature;
      for JVM simulation, `TempSensorNative`'s behavior is specified in 
      [TempSensorNative_Ext.scala](src/scala/component/building_control_gen_periodic/BuildingControl/TempSensorNative_Ext.scala).
            
    * [Fan_i.scala](src/scala/component/building_control_gen_periodic/BuildingControl/Fan_i.scala):
      The behavior is realized in 
      [Fan_i_Impl.scala](src/scala/component/building_control_gen_periodic/BuildingControl/Fan_i_Impl.scala).
      Similar to the temperatur sensor, the fan component uses a Slang extension -- `FanNative`, to actuate a fan hardware;
      for JVM simulation, `FanNative`'s behavior is specified in 
      [FanNative_Ext.scala](src/scala/component/building_control_gen_periodic/BuildingControl/FanNative_Ext.scala).
    
    * [TempControl_i.scala](src/scala/component/building_control_gen_periodic/BuildingControl/TempControl_i.scala):
      The behavior is realized in
      [TempControl_i_Impl.scala](src/scala/component/building_control_gen_periodic/BuildingControl/TempControl_i_Impl.scala).
      
  * [src/scala/data](src/scala/data): defines data structure used in component behaviors as they are declared in AADL.
  
  * [src/scala/bridge](src/scala/bridge): mediates components to ART services by providing API 
    described in the AADL standard. A bridge is auto-generated from each component from the 
    architecture description:
    [TempSensor_i_Bridge.scala](src/scala/bridge/building_control_gen_periodic/BuildingControl/TempSensor_i_Bridge.scala),
    [Fan_i_Bridge.scala](src/scala/bridge/building_control_gen_periodic/BuildingControl/Fan_i_Bridge.scala), and
    [TempControl_i_Bridge.scala](src/scala/bridge/building_control_gen_periodic/BuildingControl/TempControl_i_Bridge.scala).
  
  * [Arch.scala](src/scala/architecture/building_control_gen_periodic/Arch.scala): provides a Slang-level representation
    of the architecture description. For JVM simulation that can be started by running the
    [Demo.scala](src/scala/architecture/building_control_gen_periodic/Demo.scala) app, 
    it is used to instantiate a running multi-threaded execution of the system.  

  * [src/scala/nix](src/scala/nix): realizes each component as an independent application that can run on Unix-like
    systems that provides an inter-process communication (IPC) facility: 
    [TempSensor_i_App.scala](src/scala/nix/building_control_gen_periodic/TempSensor_i_App.scala),
    [Fan_i_App.scala](src/scala/nix/building_control_gen_periodic/Fan_i_App.scala), and
    [TempControl_i_App.scala](src/scala/nix/building_control_gen_periodic/TempControl_i_App.scala).
    In addition, a proxy (AEP) mediates a component from its data provider:
    [Fan_i_AEP.scala](src/scala/nix/building_control_gen_periodic/Fan_i_AEP.scala), and
    [TempControl_i_AEP.scala](src/scala/nix/building_control_gen_periodic/TempControl_i_AEP.scala).
    
    The apps depends on a Slang refinement of [ArtNative.scala](src/scala/art/ArtNative.scala) for Unix-like systems --
    [ArtNix.scala](src/scala/nix/building_control_gen_periodic/ArtNix.scala), that uses a more specific 
    [Platform.scala](src/scala/nix/building_control_gen_periodic/Platform.scala) extension that provides IPC API,
    as well as a platform-specific way to put a process to "sleep" for a certain amount of time 
    ([Process.scala](src/scala/nix/building_control_gen_periodic/Process.scala)).
    
    To make for a running example on Unix-like platforms, 
    [System V MessageQueue](http://beej.us/guide/bgipc/html/single/bgipc.html#mq) is chosen (primarily for simplicity),
    thus, [Platform.scala](src/scala/nix/building_control_gen_periodic/Platform.scala) is refined further as
    [MessageQueue.scala](src/scala/nix/building_control_gen_periodic/MessageQueue.scala) extension.
    
* The Slang-to-C transpiler generates C code (and CMake build definition) from the Slang implementation above.
  The generated code is located at: [src/c](src/c). 
  The only C code that has to be developed is implementing 
  [MessageQueue.scala](src/scala/nix/building_control_gen_periodic/MessageQueue.scala),
  [Process.scala](src/scala/nix/building_control_gen_periodic/Process.scala), 
  `TempSensorNative`, and `FanNative`, totalling less than 60 (sixty!) lines of C code in
  [ext.c](src/c/ext/ext.c) for native simulation purposes.
  
  The generated C code can be compiled using clang, gcc, or CompCert, with the resulting apps running under macOS, 
  Linux, and Windows/Cygwin. The generated CMake build definition is suitable for using the easy-to-use 
  [CLion](https://www.jetbrains.com/clion/) IDE for C/C++; for example, it was used to develop [ext.c](src/c/ext/ext.c).


## Natively Running The Simulation on Unix-like Host

### macOS

**Requirements**

* C compiler toolchain (XCode and its command line utilities)

* CMake (available through [MacPorts](https://www.macports.org))

[**Compiling**](bin/compile-mac.sh)

```bash
MAKE_ARGS=-j8 bin/compile-mac.sh
```

Note: the `MAKE_ARGS` above sets the compilation to use 8 cores (adjust accordingly).

[**Running**](bin/run-mac.sh)

```bash
bin/run-mac.sh
```

This will open new terminal windows for the three component apps; press the enter key to start the simulation.

[**Stopping Simulation**](bin/stop.sh)

```bash
bin/stop.sh
```

### Linux

**Requirements**

* C compiler toolchain

* CMake (available through the Linux distribution package manager)

[**Compiling**](bin/compile-linux.sh)

```bash
MAKE_ARGS=-j8 bin/compile-linux.sh
```

[**Running**](bin/run-linux.sh)

```bash
bin/run-linux.sh
```

This will open new terminal windows for the three component apps; press the enter key to start the simulation.

[**Stopping Simulation**](bin/stop.sh)

```bash
bin/stop.sh
```

### Windows (Cygwin)

**Requirements** 

Install the following packages

```bash
<path-cygwin-setup>/setup-x86_64.exe -q --packages=cygrunsrv,make,cmake,clang,procps-ng
```

Then run [cygserver](https://www.cygwin.com/cygwin-ug-net/using-cygserver.html) (needed for System V MessageQueue).

[**Compiling**](bin/compile-cygwin.sh)

```bash
MAKE_ARGS=-j8 bin/compile-cygwin.sh
```

[**Running**](bin/run-cygwin.sh)

```bash
bin/run-cygwin.sh
```

This will open new terminal windows for the three component apps; press the enter key to start the simulation.

[**Stopping Simulation**](bin/stop.sh)

```bash
bin/stop.sh
```