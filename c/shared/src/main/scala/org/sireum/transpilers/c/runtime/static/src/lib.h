#ifndef SIREUM_LIB_H
#define SIREUM_LIB_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <errno.h>

#define bool _Bool
#define true 1
#define false 0

void assert(int expression);

int memcmp(const void *str1, const void *str2, size_t n);
void *memcpy(void *dest, const void * src, size_t n);
void *memset(void *str, int c, size_t n);

size_t strlen(const char *str);
double strtod(const char *str, char **endptr);
float strtof(const char* str, char **endptr);
long double strtold(const char *str, char **endptr);
long long strtoll(const char *str, char **endptr, int base);
unsigned long long strtoull(const char *str, char **endptr, int base);

double fmod(double x, double y);
long double fmodl(long double x, long double y);
float fmodf(float x, float y);

#ifdef SIREUM_NO_STDIO
int printf(const char *, ...);
#define fprintf(file, format, ...) printf(format, ##__VA_ARGS__)
#define fflush(file)
int snprintf(char *, size_t, const char *, ...);
#else
#include <stdio.h>
#endif

void exit(int status);
void abort(void);

#ifdef __cplusplus
}
#endif

#endif