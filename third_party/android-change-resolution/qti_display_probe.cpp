#include <dlfcn.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cmath>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include <drm/drm.h>
#include <drm/drm_mode.h>

namespace DisplayConfig {
class ClientInterface;
class ConfigCallback;

struct Attributes {
  uint32_t vsync_period = 0;
  uint32_t xres = 0;
  uint32_t yres = 0;
  float xdpi = 0.0f;
  float ydpi = 0.0f;
  int panel_type = 0;
  bool is_yuv = false;
};

enum DisplayType : uint32_t {
  DISPLAY_PRIMARY = 0,
  DISPLAY_EXTERNAL = 1,
  DISPLAY_VIRTUAL = 2,
};
}  // namespace DisplayConfig

using CreateFn = int (*)(std::string, DisplayConfig::ConfigCallback *,
                         DisplayConfig::ClientInterface **);
using DestroyFn = void (*)(DisplayConfig::ClientInterface *);
using IsConnectedFn = int (*)(DisplayConfig::ClientInterface *,
                              DisplayConfig::DisplayType, bool *);
using GetConfigCountFn = int (*)(DisplayConfig::ClientInterface *,
                                 DisplayConfig::DisplayType, uint32_t *);
using GetActiveConfigFn = int (*)(DisplayConfig::ClientInterface *,
                                  DisplayConfig::DisplayType, uint32_t *);
using SetActiveConfigFn = int (*)(DisplayConfig::ClientInterface *,
                                  DisplayConfig::DisplayType, uint32_t);
using GetDisplayAttributesFn = int (*)(DisplayConfig::ClientInterface *,
                                       uint32_t,
                                       DisplayConfig::DisplayType,
                                       DisplayConfig::Attributes *);
using IsSupportedConfigSwitchFn = int (*)(DisplayConfig::ClientInterface *,
                                          uint32_t, uint32_t, bool *);

struct Api {
  void *handle = nullptr;
  std::string library;
  std::vector<std::string> load_errors;
  std::vector<std::string> missing_symbols;

  CreateFn create = nullptr;
  DestroyFn destroy = nullptr;
  IsConnectedFn is_connected = nullptr;
  GetConfigCountFn get_config_count = nullptr;
  GetActiveConfigFn get_active_config = nullptr;
  SetActiveConfigFn set_active_config = nullptr;
  GetDisplayAttributesFn get_display_attributes = nullptr;
  IsSupportedConfigSwitchFn is_supported_config_switch = nullptr;
};

static uint64_t Ptr(const void *p) {
  return reinterpret_cast<uint64_t>(p);
}

static std::string ErrnoString(int e) {
  return std::string(strerror(e)) + " (" + std::to_string(e) + ")";
}

static const char *ConnectionName(uint32_t connection) {
  switch (connection) {
    case 1:
      return "connected";
    case 2:
      return "disconnected";
    case 3:
      return "unknown";
    default:
      return "invalid";
  }
}

static const char *ConnectorTypeName(uint32_t type) {
  switch (type) {
    case 10:
      return "DP";
    case 11:
      return "HDMI-A";
    case 12:
      return "HDMI-B";
    case 14:
      return "eDP";
    case 15:
      return "Virtual";
    case 16:
      return "DSI";
    default:
      return "Unknown";
  }
}

static bool IsLikelyExternalConnector(uint32_t type) {
  return type == 10 || type == 11 || type == 12 || type == 14;
}

static std::string JsonEscape(const std::string &value) {
  std::ostringstream out;
  for (char c : value) {
    switch (c) {
      case '\\':
        out << "\\\\";
        break;
      case '"':
        out << "\\\"";
        break;
      case '\n':
        out << "\\n";
        break;
      case '\r':
        out << "\\r";
        break;
      case '\t':
        out << "\\t";
        break;
      default:
        if (static_cast<unsigned char>(c) < 0x20) {
          out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
              << static_cast<int>(static_cast<unsigned char>(c)) << std::dec;
        } else {
          out << c;
        }
    }
  }
  return out.str();
}

static void EmitStringArray(std::ostream &out,
                            const std::vector<std::string> &values) {
  out << "[";
  for (size_t i = 0; i < values.size(); ++i) {
    if (i) out << ",";
    out << "\"" << JsonEscape(values[i]) << "\"";
  }
  out << "]";
}

template <typename T>
static T LoadSymbol(Api *api, const char *label, const char *name) {
  dlerror();
  void *symbol = dlsym(api->handle, name);
  const char *error = dlerror();
  if (!symbol || error) {
    std::string message = label;
    message += "=";
    message += name;
    if (error) {
      message += " ";
      message += error;
    }
    api->missing_symbols.push_back(message);
    return nullptr;
  }
  return reinterpret_cast<T>(symbol);
}

static bool LoadApi(Api *api) {
  const char *paths[] = {
      "libdisplayconfig.qti.so",
      "/vendor/lib64/libdisplayconfig.qti.so",
      "/system_ext/lib64/libdisplayconfig.system.qti.so",
      "/system_ext/lib64/libdisplayconfig.qti.so",
  };

  for (const char *path : paths) {
    dlerror();
    api->handle = dlopen(path, RTLD_NOW);
    if (api->handle) {
      api->library = path;
      break;
    }
    const char *error = dlerror();
    std::string message = path;
    message += ": ";
    message += error ? error : "unknown dlopen failure";
    api->load_errors.push_back(message);
  }
  if (!api->handle) return false;

  api->create = LoadSymbol<CreateFn>(
      api, "ClientInterface::Create",
      "_ZN13DisplayConfig15ClientInterface6CreateENSt3__112basic_stringIcNS1_"
      "11char_traitsIcEENS1_9allocatorIcEEEEPNS_14ConfigCallbackEPPS0_");
  api->destroy = LoadSymbol<DestroyFn>(
      api, "ClientInterface::Destroy",
      "_ZN13DisplayConfig15ClientInterface7DestroyEPS0_");
  api->is_connected = LoadSymbol<IsConnectedFn>(
      api, "ClientImpl::IsDisplayConnected",
      "_ZN13DisplayConfig10ClientImpl18IsDisplayConnectedENS_11DisplayTypeEPb");
  api->get_config_count = LoadSymbol<GetConfigCountFn>(
      api, "ClientImpl::GetConfigCount",
      "_ZN13DisplayConfig10ClientImpl14GetConfigCountENS_11DisplayTypeEPj");
  api->get_active_config = LoadSymbol<GetActiveConfigFn>(
      api, "ClientImpl::GetActiveConfig",
      "_ZN13DisplayConfig10ClientImpl15GetActiveConfigENS_11DisplayTypeEPj");
  api->set_active_config = LoadSymbol<SetActiveConfigFn>(
      api, "ClientImpl::SetActiveConfig",
      "_ZN13DisplayConfig10ClientImpl15SetActiveConfigENS_11DisplayTypeEj");
  api->get_display_attributes = LoadSymbol<GetDisplayAttributesFn>(
      api, "ClientImpl::GetDisplayAttributes",
      "_ZN13DisplayConfig10ClientImpl20GetDisplayAttributesEjNS_11DisplayTypeEPNS_10AttributesE");
  api->is_supported_config_switch = LoadSymbol<IsSupportedConfigSwitchFn>(
      api, "ClientImpl::IsSupportedConfigSwitch",
      "_ZN13DisplayConfig10ClientImpl23IsSupportedConfigSwitchEjjPb");

  return api->create && api->destroy && api->is_connected &&
         api->get_config_count && api->get_active_config &&
         api->get_display_attributes;
}

static bool ParseDisplay(const std::string &value,
                         DisplayConfig::DisplayType *display) {
  if (value == "external" || value == "hdmi" || value == "dp") {
    *display = DisplayConfig::DISPLAY_EXTERNAL;
    return true;
  }
  if (value == "primary" || value == "internal") {
    *display = DisplayConfig::DISPLAY_PRIMARY;
    return true;
  }
  if (value == "virtual") {
    *display = DisplayConfig::DISPLAY_VIRTUAL;
    return true;
  }
  return false;
}

static double RefreshRateHz(uint32_t vsync_period) {
  if (!vsync_period) return 0.0;
  return 1000000000.0 / static_cast<double>(vsync_period);
}

static uint32_t ModeSelectorFromFlags(uint32_t flags) {
  return flags & 0xf;
}

static void EmitDrmDiagnostics(std::ostream &out) {
  out << "\"drm\":{";
  const char *path = "/dev/dri/card0";
  int fd = open(path, O_RDWR | O_CLOEXEC);
  if (fd < 0) {
    out << "\"ok\":false,\"path\":\"" << path << "\",\"error\":\""
        << JsonEscape(ErrnoString(errno)) << "\",\"connectors\":[]}";
    return;
  }

  drm_mode_card_res res = {};
  if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) != 0) {
    int saved_errno = errno;
    close(fd);
    out << "\"ok\":false,\"path\":\"" << path
        << "\",\"error\":\"GETRESOURCES probe failed: "
        << JsonEscape(ErrnoString(saved_errno)) << "\",\"connectors\":[]}";
    return;
  }

  std::vector<uint32_t> crtcs(res.count_crtcs);
  std::vector<uint32_t> connectors(res.count_connectors);
  std::vector<uint32_t> encoders(res.count_encoders);
  res.crtc_id_ptr = crtcs.empty() ? 0 : Ptr(crtcs.data());
  res.connector_id_ptr = connectors.empty() ? 0 : Ptr(connectors.data());
  res.encoder_id_ptr = encoders.empty() ? 0 : Ptr(encoders.data());
  if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) != 0) {
    int saved_errno = errno;
    close(fd);
    out << "\"ok\":false,\"path\":\"" << path
        << "\",\"error\":\"GETRESOURCES fill failed: "
        << JsonEscape(ErrnoString(saved_errno)) << "\",\"connectors\":[]}";
    return;
  }

  out << "\"ok\":true,\"path\":\"" << path << "\",\"connectors\":[";
  bool first_connector = true;
  for (uint32_t connector_id : connectors) {
    drm_mode_get_connector conn = {};
    conn.connector_id = connector_id;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &conn) != 0) {
      if (!first_connector) out << ",";
      first_connector = false;
      out << "{\"id\":" << connector_id << ",\"err\":\""
          << JsonEscape(ErrnoString(errno)) << "\",\"modes\":[]}";
      continue;
    }

    std::vector<drm_mode_modeinfo> modes(conn.count_modes);
    std::vector<uint32_t> encoders(conn.count_encoders);
    std::vector<uint32_t> props(conn.count_props);
    std::vector<uint64_t> prop_values(conn.count_props);
    conn.modes_ptr = modes.empty() ? 0 : Ptr(modes.data());
    conn.encoders_ptr = encoders.empty() ? 0 : Ptr(encoders.data());
    conn.props_ptr = props.empty() ? 0 : Ptr(props.data());
    conn.prop_values_ptr = prop_values.empty() ? 0 : Ptr(prop_values.data());
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &conn) != 0) {
      if (!first_connector) out << ",";
      first_connector = false;
      out << "{\"id\":" << connector_id << ",\"err\":\""
          << JsonEscape(ErrnoString(errno)) << "\",\"modes\":[]}";
      continue;
    }

    if (!first_connector) out << ",";
    first_connector = false;
    std::string label = std::string(ConnectorTypeName(conn.connector_type)) +
                        "-" + std::to_string(conn.connector_type_id);
    out << "{\"id\":" << connector_id << ",\"name\":\""
        << JsonEscape(label) << "\",\"type\":" << conn.connector_type
        << ",\"typeId\":" << conn.connector_type_id << ",\"connection\":\""
        << ConnectionName(conn.connection) << "\",\"external\":"
        << (IsLikelyExternalConnector(conn.connector_type) ? "true" : "false")
        << ",\"encoder\":" << conn.encoder_id << ",\"modes\":[";
    for (uint32_t i = 0; i < conn.count_modes && i < modes.size(); ++i) {
      const drm_mode_modeinfo &mode = modes[i];
      if (i) out << ",";
      out << "{\"index\":" << i << ",\"name\":\""
          << JsonEscape(mode.name) << "\",\"width\":" << mode.hdisplay
          << ",\"height\":" << mode.vdisplay
          << ",\"refresh\":" << mode.vrefresh
          << ",\"clock\":" << mode.clock << ",\"flags\":" << mode.flags
          << ",\"type\":" << mode.type << ",\"selector\":"
          << ModeSelectorFromFlags(mode.flags) << "}";
    }
    out << "]}";
  }
  out << "]}";
  close(fd);
}

static void EmitSymbolState(std::ostream &out, const Api &api) {
  out << "\"symbols\":{"
      << "\"create\":" << (api.create ? "true" : "false")
      << ",\"destroy\":" << (api.destroy ? "true" : "false")
      << ",\"isConnected\":" << (api.is_connected ? "true" : "false")
      << ",\"getConfigCount\":" << (api.get_config_count ? "true" : "false")
      << ",\"getActiveConfig\":" << (api.get_active_config ? "true" : "false")
      << ",\"setActiveConfig\":" << (api.set_active_config ? "true" : "false")
      << ",\"getDisplayAttributes\":"
      << (api.get_display_attributes ? "true" : "false")
      << ",\"isSupportedConfigSwitch\":"
      << (api.is_supported_config_switch ? "true" : "false") << "}";
}

static int RunDiag(DisplayConfig::DisplayType display) {
  Api api;
  const bool api_ready = LoadApi(&api);

  std::ostringstream out;
  out << std::fixed << std::setprecision(3);
  out << "{";
  out << "\"ok\":" << (api_ready ? "true" : "false");
  out << ",\"library\":\"" << JsonEscape(api.library) << "\"";
  out << ",\"display\":" << static_cast<uint32_t>(display);
  out << ",\"loadErrors\":";
  EmitStringArray(out, api.load_errors);
  out << ",\"missingSymbols\":";
  EmitStringArray(out, api.missing_symbols);
  out << ",";
  EmitSymbolState(out, api);
  out << ",";
  EmitDrmDiagnostics(out);

  if (!api_ready) {
    out << ",\"error\":\"Qualcomm displayconfig API is unavailable\"";
    out << ",\"configs\":[]}";
    std::cout << out.str() << "\n";
    return 0;
  }

  DisplayConfig::ClientInterface *client = nullptr;
  int err = api.create("android_change_resolution", nullptr, &client);
  out << ",\"create\":{\"err\":" << err << ",\"client\":"
      << (client ? "true" : "false") << "}";
  if (err != 0 || !client) {
    out << ",\"error\":\"ClientInterface::Create failed\"";
    out << ",\"configs\":[]}";
    std::cout << out.str() << "\n";
    return 0;
  }

  bool connected = false;
  err = api.is_connected(client, display, &connected);
  out << ",\"connected\":{\"err\":" << err << ",\"value\":"
      << (connected ? "true" : "false") << "}";

  uint32_t count = 0;
  err = api.get_config_count(client, display, &count);
  out << ",\"configCount\":{\"err\":" << err << ",\"value\":" << count
      << "}";

  uint32_t active = UINT32_MAX;
  int active_err = api.get_active_config(client, display, &active);
  out << ",\"activeConfig\":{\"err\":" << active_err << ",\"value\":"
      << active << "}";

  out << ",\"configs\":[";
  if (err == 0) {
    for (uint32_t config = 0; config < count; ++config) {
      if (config) out << ",";
      DisplayConfig::Attributes attrs = {};
      int attr_err = api.get_display_attributes(client, config, display, &attrs);

      out << "{\"index\":" << config << ",\"err\":" << attr_err;
      if (attr_err == 0) {
        out << ",\"width\":" << attrs.xres << ",\"height\":" << attrs.yres
            << ",\"refresh\":" << RefreshRateHz(attrs.vsync_period)
            << ",\"roundedRefresh\":"
            << static_cast<int>(std::lround(RefreshRateHz(attrs.vsync_period)))
            << ",\"vsyncPeriodNs\":" << attrs.vsync_period
            << ",\"xdpi\":" << attrs.xdpi << ",\"ydpi\":" << attrs.ydpi
            << ",\"panelType\":" << attrs.panel_type
            << ",\"isYuv\":" << (attrs.is_yuv ? "true" : "false")
            << ",\"active\":" << (config == active ? "true" : "false");
        if (api.is_supported_config_switch && active_err == 0) {
          bool supported = false;
          int switch_err =
              api.is_supported_config_switch(client, active, config, &supported);
          out << ",\"switchFromActive\":{\"err\":" << switch_err
              << ",\"supported\":" << (supported ? "true" : "false") << "}";
        }
      }
      out << "}";
    }
  }
  out << "]}";

  api.destroy(client);
  std::cout << out.str() << "\n";
  return 0;
}

static int RunSetActive(DisplayConfig::DisplayType display, const char *value) {
  char *end = nullptr;
  unsigned long config = strtoul(value, &end, 0);
  if (!end || *end != '\0' || config > UINT32_MAX) {
    std::cout << "{\"ok\":false,\"error\":\"bad config index\"}\n";
    return 2;
  }

  Api api;
  if (!LoadApi(&api) || !api.set_active_config) {
    std::cout << "{\"ok\":false,\"error\":\"SetActiveConfig unavailable\"}\n";
    return 0;
  }

  DisplayConfig::ClientInterface *client = nullptr;
  int err = api.create("android_change_resolution", nullptr, &client);
  if (err != 0 || !client) {
    std::cout << "{\"ok\":false,\"error\":\"ClientInterface::Create failed\","
              << "\"err\":" << err << "}\n";
    return 0;
  }

  err = api.set_active_config(client, display, static_cast<uint32_t>(config));
  api.destroy(client);
  std::cout << "{\"ok\":" << (err == 0 ? "true" : "false")
            << ",\"err\":" << err << ",\"config\":" << config << "}\n";
  return err == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
  if (argc < 2) {
    std::cout << "{\"ok\":false,\"error\":\"usage: qti-display-probe diag "
                 "[external|primary|virtual] | set-active CONFIG "
                 "[external|primary|virtual]\"}\n";
    return 2;
  }

  std::string command = argv[1];
  DisplayConfig::DisplayType display = DisplayConfig::DISPLAY_EXTERNAL;

  if (command == "diag") {
    if (argc >= 3 && !ParseDisplay(argv[2], &display)) {
      std::cout << "{\"ok\":false,\"error\":\"unknown display\"}\n";
      return 2;
    }
    return RunDiag(display);
  }

  if (command == "set-active") {
    if (argc < 3) {
      std::cout << "{\"ok\":false,\"error\":\"missing config index\"}\n";
      return 2;
    }
    if (argc >= 4 && !ParseDisplay(argv[3], &display)) {
      std::cout << "{\"ok\":false,\"error\":\"unknown display\"}\n";
      return 2;
    }
    return RunSetActive(display, argv[2]);
  }

  std::cout << "{\"ok\":false,\"error\":\"unknown command\"}\n";
  return 2;
}
