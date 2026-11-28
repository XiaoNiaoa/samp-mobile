#include <string>
#include "ImGuiRenderer.h"
#include "UISettings.h"

ImGuiRenderer::ImGuiRenderer(ImDrawList* draw_list, ImFont* font)
{
	m_drawList = draw_list;
	m_font = font;
}

void ImGuiRenderer::drawLine(const ImVec2& a, const ImVec2& b, const ImColor& color, float thickness)
{
	m_drawList->AddLine(a, b, color, thickness);
}

void ImGuiRenderer::drawRect(const ImVec2& a, const ImVec2& b, const ImColor& color, bool fill, float thickness)
{
	fill ? m_drawList->AddRectFilled(a, b, color) :
	m_drawList->AddRect(a, b, color, 0.0f, 15, thickness);
}

void ImGuiRenderer::drawRectFilledMulticolor(const ImVec2& a, const ImVec2& b, const ImColor& col_upr_left, const ImColor& col_upr_right, const ImColor& col_bot_right, const ImColor& col_bot_left)
{
	m_drawList->AddRectFilledMultiColor(a, b, col_upr_left, col_upr_right, col_bot_right, col_bot_left);
}

void ImGuiRenderer::drawTriangle(const ImVec2& a, const ImVec2& b, const ImVec2& c, const ImColor& color, bool fill, float thickness)
{
	fill ? m_drawList->AddTriangleFilled(a, b, c, color) :
	m_drawList->AddTriangle(a, b, c, color, thickness);
}

void ImGuiRenderer::drawConvexPolyFilled(ImVec2* points, int num_points, const ImColor& color)
{
	m_drawList->AddConvexPolyFilled(points, num_points, color);
}

void ImGuiRenderer::drawText(const ImVec2& pos, const ImColor& color, const char* begin, const char* end, bool outline, float font_size, ImFont* font)
{
	ImFont* pFont = font == nullptr ? this->m_font : font;
	float sz_font = font_size == 0.0f ? (pFont->FontSize * pFont->Scale) : font_size;

	if (outline) {
		const float outlineSize = UISettings::outlineSize();
		const ImU32 shadowCol = IM_COL32(0, 0, 0, 255);
		m_drawList->AddText(pFont, sz_font, ImVec2(pos.x + outlineSize, pos.y), shadowCol, begin, end);
		m_drawList->AddText(pFont, sz_font, ImVec2(pos.x - outlineSize, pos.y), shadowCol, begin, end);
		m_drawList->AddText(pFont, sz_font, ImVec2(pos.x, pos.y + outlineSize), shadowCol, begin, end);
		m_drawList->AddText(pFont, sz_font, ImVec2(pos.x, pos.y - outlineSize), shadowCol, begin, end);
	}

	m_drawList->AddText(pFont, sz_font, pos, color, begin, end);
}

void ImGuiRenderer::drawText(const ImVec2& pos, const ImColor& color, const std::string& text, bool outlined, float font_size, ImFont* font)
{
	if (text.empty()) return;
	drawTextRange(pos, color, text.c_str(), text.c_str() + text.length(), outlined, font_size, font, nullptr);
}

void ImGuiRenderer::drawTextRange(const ImVec2& pos, const ImColor& color, const char* begin, const char* end, bool outlined, float font_size, ImFont* font, ImColor* outLastColor)
{
	if (!begin || !end || begin >= end) {
		if (outLastColor) *outLastColor = color;
		return;
	}

	ImFont* pFont = font == nullptr ? this->m_font : font;

	float sz_font = font_size == 0.0f ? (pFont->FontSize * pFont->Scale) : font_size;

	const char* text_start = begin;
	const char* text_cur = begin;
	const char* text_end = end;

	ImVec2 pos_cur = pos;
	ImColor color_cur = color;

	while (text_cur < text_end && *text_cur) {
		ImVec4 parsedCol;
		if (*text_cur == '{' && ((&text_cur[7] < text_end) && text_cur[7] == '}')
			&& processInlineHexColor(text_cur + 1, text_cur + 7, parsedCol)) {
			// print accumulated text
			if (text_cur != text_start) {
				drawText(pos_cur, color_cur, text_start, text_cur, outlined, sz_font, pFont);
				ImVec2 sz = pFont->CalcTextSizeA(sz_font, FLT_MAX, 0.0f, text_start, text_cur);
				pos_cur.x += sz.x;
			}

			// preserve existing alpha (e.g. semi-transparent 3D labels / TextDraws)
			parsedCol.w = color_cur.Value.w;
			color_cur = parsedCol;

			text_cur += 7;
			text_start = text_cur + 1;
		}
		else if (*text_cur == '\n') {
			// print accumulated text
			if (text_cur != text_start) {
				drawText(pos_cur, color_cur, text_start, text_cur, outlined, sz_font, pFont);
			}

			pos_cur.x = pos.x;
			pos_cur.y += sz_font;
			text_start = text_cur + 1;
		}
		else if (*text_cur == '\t') {
			// print accumulated text
			if (text_cur != text_start) {
				drawText(pos_cur, color_cur, text_start, text_cur, outlined, sz_font, pFont);
				ImVec2 sz = pFont->CalcTextSizeA(sz_font, FLT_MAX, 0.0f, text_start, text_cur);
				pos_cur.x += sz.x;
			}

			pos_cur.x += sz_font;
			text_start = text_cur + 1;
		}

		++text_cur;
	}

	if (text_cur != text_start) {
		drawText(pos_cur, color_cur, text_start, text_cur, outlined, sz_font, pFont);
	}
	if (outLastColor) {
		*outLastColor = color_cur;
	}
}

ImVec2 ImGuiRenderer::calculateTextSize(const std::string& text, float font_size)
{
	if (text.empty()) return ImVec2(0.0f, 0.0f);
	return calculateTextSizeRange(text.c_str(), text.c_str() + text.length(), font_size);
}

ImVec2 ImGuiRenderer::calculateTextSize(const char* begin, const char* end, float font_size)
{
	return m_font->CalcTextSizeA(font_size == 0.0f ? (m_font->FontSize * m_font->Scale) : font_size, FLT_MAX, 0.0f, begin, end);
}

ImVec2 ImGuiRenderer::calculateTextSizeRange(const char* begin, const char* end, float font_size)
{
	ImVec2 text_size = {0.0f, 0.0f};
	if (!begin || !end || begin >= end) return text_size;

	ImVec2 cur_size = {0.0f, 0.0f};
	if (font_size == 0.0f) font_size = m_font->FontSize * m_font->Scale;

	const char* text_start = begin;
	const char* text_cur = begin;
	const char* text_end = end;

	while (text_cur < text_end && *text_cur) {
		ImVec4 dummyCol;
		if (*text_cur == '{' && ((&text_cur[7] < text_end) && text_cur[7] == '}')
			&& processInlineHexColor(text_cur + 1, text_cur + 7, dummyCol)) {
			if (text_cur != text_start) {
				ImVec2 sz = calculateTextSize(text_start, text_cur, font_size);
				cur_size.x += sz.x;
				if (cur_size.y == 0.0f) cur_size.y = sz.y;
			}

			text_cur += 7;
			text_start = text_cur + 1;
		}
		else if (*text_cur == '\n') {
			if (text_cur != text_start) {
				ImVec2 sz = calculateTextSize(text_start, text_cur, font_size);
				cur_size.x += sz.x;
				if (cur_size.y == 0.0f) cur_size.y = sz.y;
			}

			text_size.x = ImMax(text_size.x, cur_size.x);
			cur_size.y += font_size;
			cur_size.x = 0.0f;

			text_start = text_cur + 1;
		}
		else if (*text_cur == '\t') {
			if (text_cur != text_start) {
				ImVec2 sz = calculateTextSize(text_start, text_cur, font_size);
				cur_size.x += sz.x;
				if (cur_size.y == 0.0f) cur_size.y = sz.y;
			}

			cur_size.x += font_size;
			text_start = text_cur + 1;
		}

		++text_cur;
	}

	if (text_cur != text_start) {
		ImVec2 sz = calculateTextSize(text_start, text_cur, font_size);
		cur_size.x += sz.x;
		if (cur_size.y == 0.0f) cur_size.y = sz.y;
	}

	text_size = ImMax(text_size, cur_size);
	return text_size;
}

bool ImGuiRenderer::processInlineHexColor(const char* start, const char* end, ImVec4& color)
{
	if (!start || !end || (end - start) != 6) {
		return false;
	}
	unsigned int hexColor = 0;
	for (int i = 0; i < 6; ++i) {
		const unsigned char c = static_cast<unsigned char>(start[i]);
		unsigned int nibble = 0;
		if (c >= '0' && c <= '9') nibble = c - '0';
		else if (c >= 'a' && c <= 'f') nibble = 10 + (c - 'a');
		else if (c >= 'A' && c <= 'F') nibble = 10 + (c - 'A');
		else return false;
		hexColor = (hexColor << 4) | nibble;
	}
	color.x = static_cast<float>((hexColor & 0x00FF0000u) >> 16) / 255.0f;
	color.y = static_cast<float>((hexColor & 0x0000FF00u) >> 8) / 255.0f;
	color.z = static_cast<float>((hexColor & 0x000000FFu)) / 255.0f;
	color.w = 1.0f;
	return true;
}

void ImGuiRenderer::drawImage(const ImVec2& a, const ImVec2& b, ImTextureID texture)
{
	m_drawList->AddImage(texture, a, b);
}

void ImGuiRenderer::pushClipRect(const ImVec2& min, const ImVec2& max, bool intersect)
{
	m_drawList->PushClipRect(min, max, intersect);
}

void ImGuiRenderer::popClipRect()
{
	m_drawList->PopClipRect();
}