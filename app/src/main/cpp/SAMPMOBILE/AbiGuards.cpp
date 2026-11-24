// Compile-time dual-ABI layout and wire-protocol invariants.
// Compiled for both arm64-v8a and armeabi-v7a to catch struct drift or
// pointer-width regressions immediately at build time.

#include "Client.h"
// 2026-11-22: Net/*.h 里的 CVehicle / CPlayerPed / CObject / CTextDraw / TEXT_DRAW_TRANSMIT
// 用的是项目 Game/ 层导出的全局类型，不是 sa:: 里的同名结构；少了这一行
// 单独编译本文件会报 unknown type name（其它 TU 都是先包含了 Game/Game.h 才不出事）。
#include "Game/Game.h"
#include "Net/NetGame.h"
#include "GameSA/include/PlayerInfo.h"
#include "GameSA/include/Entity/Object/Object.h"

#include <cstddef>
#include <cstdint>

// 1. Pointer width must match VER_x32 build flag.
static_assert(sizeof(void*) == (VER_x32 ? 4 : 8), "Pointer width mismatches VER_x32");
static_assert(sizeof(uintptr_t) == sizeof(void*), "uintptr_t width mismatches void*");

// 2. RakNet wire types (prevents arm64 8-byte unsigned long timestamp shift).
static_assert(sizeof(RakNetTime) == 4, "RakNetTime must be 4 bytes on both ABIs");
static_assert(sizeof(MessageID) == 1, "MessageID must be 1 byte");
static_assert(sizeof(RPCIndex) == 1, "RPCIndex must be 1 byte");
static_assert(sizeof(PlayerIndex) == 2, "PlayerIndex must be 2 bytes");

// 3. SA-MP packed network sync structures (wire-protocol exact byte sizes).
static_assert(sizeof(PLAYER_SPAWN_INFO) == 46, "PLAYER_SPAWN_INFO wire size mismatch");
static_assert(sizeof(PLAYER_SPAWN_INFO_DL) == 50, "PLAYER_SPAWN_INFO_DL wire size mismatch");
static_assert(sizeof(ONFOOT_SYNC_DATA) == 68, "ONFOOT_SYNC_DATA wire size mismatch");
static_assert(sizeof(INCAR_SYNC_DATA) == 63, "INCAR_SYNC_DATA wire size mismatch");
static_assert(sizeof(PASSENGER_SYNC_DATA) == 24, "PASSENGER_SYNC_DATA wire size mismatch");
static_assert(sizeof(UNOCCUPIED_SYNC_DATA) == 67, "UNOCCUPIED_SYNC_DATA wire size mismatch");
static_assert(sizeof(TRAILER_SYNC_DATA) == 54, "TRAILER_SYNC_DATA wire size mismatch");
static_assert(sizeof(AIM_SYNC_DATA) == 31, "AIM_SYNC_DATA wire size mismatch");
static_assert(sizeof(BULLET_SYNC_DATA) == 40, "BULLET_SYNC_DATA wire size mismatch");
static_assert(sizeof(SPECTATOR_SYNC_DATA) == 18, "SPECTATOR_SYNC_DATA wire size mismatch");

// 4. Core GTA:SA Android v2.10 dual-ABI engine structs.
static_assert(sizeof(sa::CVector) == 12, "sa::CVector must be 12 bytes");
static_assert(sizeof(sa::CQuaternion) == 16, "sa::CQuaternion must be 16 bytes");
static_assert(sizeof(sa::CPlayerInfo) == (VER_x32 ? 0x194 : 0x1D8), "sa::CPlayerInfo ABI size mismatch");
static_assert(sizeof(sa::CObject) == (VER_x32 ? 0x184 : 0x1F8), "sa::CObject ABI size mismatch");
