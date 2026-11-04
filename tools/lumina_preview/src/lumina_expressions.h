#ifndef LUMINA_EXPRESSIONS_H
#define LUMINA_EXPRESSIONS_H
#include <stdint.h>

#define LUMINA_EXPRESSION_COUNT 50

typedef struct {
    const char *id;
    const char *label;
    const char *source_png;
} lumina_expression_t;

extern const lumina_expression_t lumina_expressions[LUMINA_EXPRESSION_COUNT];
const lumina_expression_t *lumina_expression(uint8_t index);

#endif
