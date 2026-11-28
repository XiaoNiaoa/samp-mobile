#pragma once

#include <SA.h>
#include <Model/Col/ColModel.h>
#include <Model/Col/CollisionData.h>
#include <Model/Col/ColTriangle.h>
#include <Core/Vector.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace colaccel {

// Binary-exact GTA:SA TFaceGroup layout verified via arm64 (0x3a0088..0x3a0154) and
// arm32 (0x2dd7b8..0x2dd860) disassembly of CCollision::ProcessColModels:
//   - colData->m_modelSec points to an int32_t holding nFaceGroups.
//   - Immediately BEFORE m_modelSec in memory lie nFaceGroups contiguous 28-byte TFaceGroup
//     records; group[i] is at reinterpret_cast<const TFaceGroup*>(m_modelSec) - (i + 1).
//   - startFace and endFace are signed int16_t inclusive indices into colData->m_pTriangles[].
//   - CCollisionData::RemoveCollisionVolumes() (arm64 0x3a3e14 / arm32 0x2e11da) never frees
//     m_modelSec, so the buffer is safely owned externally and detached before CColModel::~CColModel.
struct TFaceGroup {
	float minX;
	float minY;
	float minZ;
	float maxX;
	float maxY;
	float maxZ;
	int16_t startFace;
	int16_t endFace;
};
static_assert(sizeof(TFaceGroup) == 28, "TFaceGroup must match GTA:SA 28-byte engine layout");

// Builds spatial TFaceGroup clusters in-place on a freshly loaded CColModel (after MakeMultipleAlloc)
// by reordering colData->m_pTriangles into tight axis-aligned leaf clusters (~16 tris/group) and
// attaching an owned buffer to colData->m_modelSec + setting colData->bHasFaceGroups = 1.
// Returns the heap buffer (caller owns via delete[] and must clear bHasFaceGroups/m_modelSec before
// CColModel_Dtor), or nullptr if the mesh is small/unsuitable (<= 32 tris or > 32767 tris).
inline uint8_t* buildFaceGroupsForColModel(sa::CColModel* col)
{
	if (!col || !col->m_pColData) {
		return nullptr;
	}
	sa::CCollisionData* data = col->m_pColData;
	const int32_t numTris = static_cast<int32_t>(data->m_nNumTriangles);
	if (numTris <= 32 || numTris > 32767) {
		return nullptr;
	}
	if (!data->m_pVertices || !data->m_pTriangles || data->m_pTrianglePlanes != nullptr ||
	    data->bHasFaceGroups != 0 || data->m_modelSec != nullptr) {
		return nullptr;
	}

	struct TriEntry {
		sa::CColTriangle tri;
		float cx, cy, cz;
		float minX, minY, minZ;
		float maxX, maxY, maxZ;
	};

	const sa::CVector* verts = data->m_pVertices;
	std::vector<TriEntry> work;
	work.resize(static_cast<size_t>(numTris));

	for (int32_t i = 0; i < numTris; ++i) {
		const sa::CColTriangle& t = data->m_pTriangles[i];
		if (t.vA < 0 || t.vB < 0 || t.vC < 0 ||
		    t.vA > 65535 || t.vB > 65535 || t.vC > 65535) {
			return nullptr;
		}
		const sa::CVector& a = verts[t.vA];
		const sa::CVector& b = verts[t.vB];
		const sa::CVector& c = verts[t.vC];
		if (!std::isfinite(a.x) || !std::isfinite(a.y) || !std::isfinite(a.z) ||
		    !std::isfinite(b.x) || !std::isfinite(b.y) || !std::isfinite(b.z) ||
		    !std::isfinite(c.x) || !std::isfinite(c.y) || !std::isfinite(c.z)) {
			return nullptr;
		}
		TriEntry& e = work[static_cast<size_t>(i)];
		e.tri = t;
		e.minX = std::min({a.x, b.x, c.x});
		e.minY = std::min({a.y, b.y, c.y});
		e.minZ = std::min({a.z, b.z, c.z});
		e.maxX = std::max({a.x, b.x, c.x});
		e.maxY = std::max({a.y, b.y, c.y});
		e.maxZ = std::max({a.z, b.z, c.z});
		e.cx = (e.minX + e.maxX) * 0.5f;
		e.cy = (e.minY + e.maxY) * 0.5f;
		e.cz = (e.minZ + e.maxZ) * 0.5f;
	}

	constexpr int32_t kLeafTris = 16;
	constexpr int32_t kMaxDepth = 11; // at most 2048 leaves
	constexpr float kBoxPad = 1.0e-3f;

	std::vector<TFaceGroup> groups;
	groups.reserve(static_cast<size_t>((numTris + kLeafTris - 1) / kLeafTris));

	auto partition = [&](auto& self, int32_t begin, int32_t end, int32_t depth) -> void {
		const int32_t count = end - begin;
		if (count <= 0) {
			return;
		}

		float cMinX = work[begin].cx, cMaxX = cMinX;
		float cMinY = work[begin].cy, cMaxY = cMinY;
		float cMinZ = work[begin].cz, cMaxZ = cMinZ;
		for (int32_t i = begin + 1; i < end; ++i) {
			const TriEntry& e = work[i];
			if (e.cx < cMinX) cMinX = e.cx; else if (e.cx > cMaxX) cMaxX = e.cx;
			if (e.cy < cMinY) cMinY = e.cy; else if (e.cy > cMaxY) cMaxY = e.cy;
			if (e.cz < cMinZ) cMinZ = e.cz; else if (e.cz > cMaxZ) cMaxZ = e.cz;
		}

		const float extX = cMaxX - cMinX;
		const float extY = cMaxY - cMinY;
		const float extZ = cMaxZ - cMinZ;
		int axis = 0;
		float maxExt = extX;
		if (extY > maxExt) { axis = 1; maxExt = extY; }
		if (extZ > maxExt) { axis = 2; maxExt = extZ; }

		if (count <= kLeafTris || depth >= kMaxDepth || maxExt < 1.0e-4f) {
			float bMinX = work[begin].minX, bMaxX = work[begin].maxX;
			float bMinY = work[begin].minY, bMaxY = work[begin].maxY;
			float bMinZ = work[begin].minZ, bMaxZ = work[begin].maxZ;
			for (int32_t i = begin + 1; i < end; ++i) {
				const TriEntry& e = work[i];
				if (e.minX < bMinX) bMinX = e.minX;
				if (e.minY < bMinY) bMinY = e.minY;
				if (e.minZ < bMinZ) bMinZ = e.minZ;
				if (e.maxX > bMaxX) bMaxX = e.maxX;
				if (e.maxY > bMaxY) bMaxY = e.maxY;
				if (e.maxZ > bMaxZ) bMaxZ = e.maxZ;
			}
			TFaceGroup fg{};
			fg.minX = bMinX - kBoxPad;
			fg.minY = bMinY - kBoxPad;
			fg.minZ = bMinZ - kBoxPad;
			fg.maxX = bMaxX + kBoxPad;
			fg.maxY = bMaxY + kBoxPad;
			fg.maxZ = bMaxZ + kBoxPad;
			fg.startFace = static_cast<int16_t>(begin);
			fg.endFace = static_cast<int16_t>(end - 1);
			groups.push_back(fg);
			return;
		}

		const int32_t mid = begin + count / 2;
		if (axis == 0) {
			std::nth_element(work.begin() + begin, work.begin() + mid, work.begin() + end,
				[](const TriEntry& a, const TriEntry& b) { return a.cx < b.cx; });
		} else if (axis == 1) {
			std::nth_element(work.begin() + begin, work.begin() + mid, work.begin() + end,
				[](const TriEntry& a, const TriEntry& b) { return a.cy < b.cy; });
		} else {
			std::nth_element(work.begin() + begin, work.begin() + mid, work.begin() + end,
				[](const TriEntry& a, const TriEntry& b) { return a.cz < b.cz; });
		}

		self(self, begin, mid, depth + 1);
		self(self, mid, end, depth + 1);
	};

	partition(partition, 0, numTris, 0);

	if (groups.size() < 2u) {
		return nullptr;
	}

	const size_t nGroups = groups.size();
	const size_t bufBytes = nGroups * sizeof(TFaceGroup) + sizeof(int32_t);
	auto* buf = new (std::nothrow) uint8_t[bufBytes];
	if (!buf) {
		return nullptr;
	}

	// Commit reordered triangles back into CCollisionData::m_pTriangles
	for (int32_t i = 0; i < numTris; ++i) {
		data->m_pTriangles[i] = work[static_cast<size_t>(i)].tri;
	}

	std::memcpy(buf, groups.data(), nGroups * sizeof(TFaceGroup));
	auto* countPtr = reinterpret_cast<int32_t*>(buf + nGroups * sizeof(TFaceGroup));
	*countPtr = static_cast<int32_t>(nGroups);

	data->m_modelSec = countPtr;
	data->bHasFaceGroups = 1;
	return buf;
}

inline void detachAndFreeFaceGroups(sa::CColModel* col, uint8_t*& buf) noexcept
{
	if (col && col->m_pColData && buf) {
		col->m_pColData->bHasFaceGroups = 0;
		col->m_pColData->m_modelSec = nullptr;
	}
	delete[] buf;
	buf = nullptr;
}

// Fast slab intersection test between a 3D line segment [s, s + d] (t in [0, 1]) and an AABB.
// Uses precomputed segment direction d and pad to avoid false negatives on axis-parallel rays.
inline bool segmentIntersectsFaceGroup(const sa::CVector& s, const sa::CVector& d,
	const TFaceGroup& fg) noexcept
{
	float tmin = 0.0f;
	float tmax = 1.0f;

	// X slab
	if (std::fabs(d.x) < 1.0e-8f) {
		if (s.x < fg.minX || s.x > fg.maxX) return false;
	} else {
		const float inv = 1.0f / d.x;
		float t1 = (fg.minX - s.x) * inv;
		float t2 = (fg.maxX - s.x) * inv;
		if (t1 > t2) std::swap(t1, t2);
		if (t1 > tmin) tmin = t1;
		if (t2 < tmax) tmax = t2;
		if (tmin > tmax) return false;
	}

	// Y slab
	if (std::fabs(d.y) < 1.0e-8f) {
		if (s.y < fg.minY || s.y > fg.maxY) return false;
	} else {
		const float inv = 1.0f / d.y;
		float t1 = (fg.minY - s.y) * inv;
		float t2 = (fg.maxY - s.y) * inv;
		if (t1 > t2) std::swap(t1, t2);
		if (t1 > tmin) tmin = t1;
		if (t2 < tmax) tmax = t2;
		if (tmin > tmax) return false;
	}

	// Z slab
	if (std::fabs(d.z) < 1.0e-8f) {
		if (s.z < fg.minZ || s.z > fg.maxZ) return false;
	} else {
		const float inv = 1.0f / d.z;
		float t1 = (fg.minZ - s.z) * inv;
		float t2 = (fg.maxZ - s.z) * inv;
		if (t1 > t2) std::swap(t1, t2);
		if (t1 > tmin) tmin = t1;
		if (t2 < tmax) tmax = t2;
		if (tmin > tmax) return false;
	}

	return true;
}

} // namespace colaccel
