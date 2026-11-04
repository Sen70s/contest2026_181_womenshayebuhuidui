/* host 预览用最小 cJSON 声明（不是实现）。
 * weather_page.c 只用到 Parse/GetObjectItem/IsNumber/Delete + valuedouble；
 * 实际预览走共享缓存命中路径，不会解析 JSON。 */
#ifndef PREVIEW_FAKE_CJSON_H
#define PREVIEW_FAKE_CJSON_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct cJSON
{
  struct cJSON *next;
  struct cJSON *prev;
  struct cJSON *child;
  int           type;
  char         *valuestring;
  int           valueint;
  double        valuedouble;
  char         *string;
} cJSON;

cJSON *cJSON_Parse(const char *value);
cJSON *cJSON_GetObjectItem(const cJSON *object, const char *string);
int    cJSON_IsNumber(const cJSON *item);
void   cJSON_Delete(cJSON *item);

#ifdef __cplusplus
}
#endif

#endif /* PREVIEW_FAKE_CJSON_H */
