//
// Created by Tungsten on 2025/8/24.
//

#ifndef ENCHANTNET_EASYTIER_H
#define ENCHANTNET_EASYTIER_H

#include <cstddef>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

struct KeyValuePair {
    const char* key;
    const char* value;
};

int32_t set_tun_fd(const char* inst_name, int fd);

int parse_config(const char* cfg_str);
int run_network_instance(const char* cfg_str);
int retain_network_instance(const char** names, size_t count);
int collect_network_infos(KeyValuePair* infos, size_t max_length);

void get_error_msg(const char** out);
void free_string(const char* s);

#ifdef __cplusplus
}
#endif

#endif //ENCHANTNET_EASYTIER_H
