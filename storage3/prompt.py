import os
from enum import Enum
from typing import Optional
import json
from openai import AsyncOpenAI
from configparser import ConfigParser
from pydantic import BaseModel, Field

config = ConfigParser()
config.read('config.ini')
OPENAI_API_KEY = os.getenv('OPENAI_API_KEY', config['api']['API_KEY'])
OPENAI_ORG_ID = os.getenv('OPENAI_ORG_ID', config['api']['ORG_KEY'])
OPENAI_PROJECT_ID = os.getenv('OPENAI_PROJECT_ID', config['api']['PROJECT_KEY'])

client = AsyncOpenAI(
    api_key=OPENAI_API_KEY,
    organization=OPENAI_ORG_ID,
    project=OPENAI_PROJECT_ID
)

class Sentiment(str, Enum):
    POSITIVE = "positive"
    NEGATIVE = "negative"
    NEUTRAL = "neutral"

class ArticleAnalysis(BaseModel):
    summary: str = Field(..., description="Summary of the article (around 200 words)")
    sentiment: Sentiment = Field(..., description="Sentiment analysis result")

class ArticleUsage(BaseModel):
    prompt_tokens: int
    completion_tokens: int

async def analyze_article(article_content: str) -> ArticleAnalysis:
    """
    Analyzes an article and returns a structured response with summary and sentiment.
    
    Args:
        article_content: The content of the article to analyze
        
    Returns:
        ArticleAnalysis: A Pydantic model containing the summary and sentiment
    """
    prompt = (
        "Hãy tóm tắt bài báo sau đây và đánh giá nội dung theo 3 mức độ: "
        "'positive', 'negative', hoặc 'neutral'. Trả về kết quả dưới dạng JSON với cấu trúc sau:\n\n"
        "{\n"
        '  "summary": "",\n'
        '  "sentiment": "positive | negative | neutral"\n'
        "}\n\n"
        "Lưu ý:\n"
        "- 'summary' chỉ nên dài khoảng 200 từ, tóm gọn ý chính của bài báo.\n"
        "- 'sentiment' phải là một trong ba giá trị: 'positive', 'negative', hoặc 'neutral'.\n\n"
        "Dưới đây là nội dung bài báo:\n\n"
        f"{article_content}"
    )
    
    response = await client.beta.chat.completions.parse(
        model="gpt-4o-mini-2024-07-18",
        messages=[
            {"role": "system", "content": "AI assistant. Use Pydantic model for response."},
            {"role": "user", "content": prompt}
        ],
        response_format=ArticleAnalysis     
    )

    analysis = response.choices[0].message.parsed
    usage = ArticleUsage(
        prompt_tokens=response.usage.prompt_tokens,
        completion_tokens=response.usage.completion_tokens
    )
    
    return analysis, usage

async def main():
    article = """
    <p>Báo cáo trình Quốc hội về việc bổ sung Kế hoạch đầu tư công trung hạn vốn ngân sách trung ương giai đoạn 2021-2025 từ nguồn dự phòng chung tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023 cho các dự án đầu tư công tại phiên họp ngày 27/6/2024, Bộ trưởng Bộ Tài chính Hồ Đức Phớc nhấn mạnh nguyên tắc, tiêu chí phân bổ nguồn tăng thu Ngân sách Trung ương năm 2023 tập trung chủ yếu cho các dự án thuộc ngành quốc phòng an ninh; các dự án giao thông quan trọng quốc gia, dự án xây dựng đường cao tốc trọng điểm, dự án giao thông giúp liên kết vùng, có tác động lan tỏa, giúp phát triển kinh tế xã hội của các địa phương; các dự án thực hiện chiến lược cải cách tư pháp.</p>    <h3 class="aligncenter"><strong>KIẾN NGHỊ DÙNG HƠN 18.000 TỶ ĐỒNG  CHO 14 DỰ ÁN TRỌNG ĐIỂM</strong></h3>    <p>Về dự kiến phương án phân bổ, Bộ trưởng Bộ Tài chính cho biết, trong tổng số 26.900 tỷ đồng sử dụng Ngân sách Trung ương năm 2023 dự kiến bố trí cho 20 dự án thuộc 4 nhóm ngành, lĩnh vực gồm: Quốc phòng (2.000 tỷ đồng); an ninh (4.000 tỷ đồng); giao thông (19.380 tỷ đồng); cải cách tư pháp (1.520 tỷ đồng).</p>    <p>Trong số vốn dự kiến phân bổ trên, thẩm quyền của Ủy ban Thường vụ Quốc hội quyết định 8.680 tỷ đồng dự kiến bố trí từ nguồn tăng thu Ngân sách Trung ương năm 2023 cho 6 dự án của Bộ Giao thông vận tải đã được giap kế hoạch đầu tư công trung hạn giai đoạn 2021- 2025, đảm bảo đầy đủ thủ tục đầu tư. Nội dung này đã được Chính phủ báo cáo Ủy ban Thường vụ Quốc hội theo thẩm quyền, ông Phớc thông tin.</p>    <p>Thuộc thẩm quyền quyết định của Quốc hội, 18.220 tỷ đồng dự kiến bố trí từ nguồn tăng thu Ngân sách Trung ương năm 2023 cho 14 dự án cần báo cáo Quốc hội để bổ sung hạn mức kế hoạch đầu tư công trung hạn giai đoạn 2021- 2025 từ nguồn dự phòng chung tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023.</p>    <p>Cụ thể, 13.700 tỷ đồng cho các dự án bảo đảm cân đối đủ nguồn vốn để phê duyệt chủ trương, điểu chỉnh chủ trương đầu tư theo quy định. Trong đó, 10.200 tỷ đồng cho 4 dự án đã có trong kế hoạch đầu tư công trung hạn giai đoạn 2021-2025 (mở rộng quy mô từ 2 làn xe lên 4 làn xe hoàn chỉ cho dự án Cam Lộ-La Sơn 7000 tỷ đồng, dự án cao tốc Tuyên Quang- Hà Giang giai đoạn 1, đoạn qua tỉnh Hà Giang 1.500 tỷ đồng; Dự án xây dựng Trung tâm dữ liệu quốc gia số một của Bộ Công an 1.500 tỷ đồng; dự án Đường Nguyễn Hoàng và cầu Vượt sông Hương của tỉnh Thừa Thiên Huế 200 tỷ đồng). 3.500 tỷ đồng cho 7 dự án khởi công mới, dự án chưa có trong kế hoạch đầu tư công trung hạn.</p>    <p>Còn 4.520 tỷ đồng bố trí cho 4 dự án chưa cân đối đủ nguồn để phê duyệt hoặc điều chỉnh chủ trương đầu tư, gồm: 2.000 tỷ đồng cho dự án cao tốc Tuyên Quang- Hà Giang giai đoạn 1, đoạn qua tỉnh Tuyên Quang; 2.520 tỷ đồng cho 3 dự án chưa có trong kế hoạch đầu tư công trung hạn (Dự án sân bay Gia Bình của Bộ Công an là 1.000 tỷ đồng; 2 dự án của Tòa án nhân dân tối cao để đầu tư sửa chữa, cải tạo, nâng cấp và xây dựng trụ sở tòa án nhân dân các cấp là 1.520 tỷ đồng).</p>    <p>Chính phủ kiến nghị Quốc hội cho phép sử dụng 18.220 tỷ đồng trên và cho phép hoàn thiện thủ tục đầu tư đối với các dự án từ nguồn dự phòng chung nguồn Ngân sách Trung ương của Kế hoạch đầu tư công trung hạn giai đoạn 2021- 2025 tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023.</p>    <p>Đối với 4 dự án: Dự án sân bay Gia Bình; Xây dựng mới trụ sở Tòa án nhân dân tối cao tại 262 Đội cấn; dự án đầu tư sửa chữa, cải tạo, nâng cấp và xây dựng trụ sở Tòa án nhân dân các cấp (giai đoạn 1), Dự án cao tốc Tuyên Quang- Hà Giang giai đoạn 1 đoạn qua tỉnh Tuyên Quang, Chính phủ kiến nghị Quốc hội cho phép cấp có thẩm quyền quyết định chủ trương đầu tư dự án căn cứ nguồn vốn và mức vốn dự kiến bố trí cho dự án từ nguồn vốn tăng thu Ngân sách Trung ương năm 2023, nguồn vốn giai đoạn 2026-2030 và các nguồn vốn hợp pháp khác (nếu có) để hoàn thiện thủ tục đầu tư theo quy định.</p>    <h3 class="aligncenter"><strong>BẢO ĐẢM BỐ TRÍ VỐN CÁC DỰ ÁN CÓ TRỌNG TÂM</strong></h3>    <p>Báo cáo thẩm tra nội dung này, Chủ nhiệm Ủy ban Tài chính Ngân sách của Quốc hội Lê Quang Mạnh cho rằng việc Chính phủ trình Quốc hội cho phép sử dụng 18.220 tỷ đồng dự phòng chung nguồn Ngân sách Trung ương của Kế hoạch đầu tư công trung hạn giai đoạn 2021-2025 tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023 cho các nhiệm vụ, dự án đầu tư công là phù hợp với quy định của Luật Đầu tư công, khoản 6 Điều 6 của Nghị quyết số 29/2021/QH15 của Quốc hội. Đồng thời, danh mục các dự án kèm theo Tờ trình của Chính phủ đã được cấp có thẩm quyền đồng ý về chủ trương.</p>    <p>Theo báo cáo của Chính phủ, đây là các dự án quan trọng, cấp thiết để triển khai thực hiện các kết luận của Trung ương Đảng, Bộ Chính trị về Chiến lược cải cách tư pháp đến năm 2020; xây dựng kết cấu hạ tầng đồng bộ, nhằm đưa Việt Nam cơ bản trở thành nước công nghiệp theo hướng hiện đại...</p>    <p>Bên cạnh đó, đề nghị Chính phủ chịu trách nhiệm về việc rà soát, hoàn thiện thủ tục đầu tư, đảm bảo bố trí nguồn vốn và khả năng cân đối vốn theo đúng quy định của pháp luật; đảm bảo triển khai hiệu quả, đẩy nhanh tiến độ giải ngân và không để thất thoát, lãng phí.</p>    <p>Trên cơ sở phân tích, Ủy ban Tài chính, Ngân sách kiến nghị Quốc hội xem xét, cho phép sử dụng 18.220 tỷ đồng dự phòng chung nguồn Ngân sách Trung ương của Kế hoạch đầu tư công trung hạn giai đoạn 2021- 2025 tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023.</p>    <p>Cùng với đó cho phép hoàn thiện thủ tục đầu tư đối với các dự án từ nguồn dự phòng chung nguồn Ngân sách Trung ương của Kế hoạch đầu tư công trung hạn giai đoạn 2021- 2025 tương ứng với nguồn tăng thu Ngân sách Trung ương năm 2023.</p>    <p>Riêng các dự án chưa cân đối đủ nguồn để phê duyệt chủ trương đầu tư, vượt quá 20% tổng số vốn Kế hoạch đầu tư công trung hạn giai đoạn trước thì cho phép cấp có thẩm quyền quyết định chủ trương đầu tư các dự án căn cứ nguồn vốn và mức vốn tăng thu Ngân sách Trung ương năm 2023 dự kiến bố trí cho dự án, nguồn vốn giai đoạn 2026- 2030 và các nguồn vốn hợp pháp khác (nếu có).</p>    <p>Cơ quan thẩm tra kiến nghị Quốc hội giao Chính phủ bảo đảm nguồn vốn và khả năng cân đối vốn để bố trí đủ số vốn còn thiếu so với dự kiến tổng mức đầu tư của các dự án theo đúng quy định của pháp luật.</p>    <p>Chính phủ chịu trách nhiệm về nội dung đề xuất, danh mục dự án, bảo đảm việc bố trí vốn cho các dự án có trọng tâm, trọng điểm, bảo đảm hiệu quả, đẩy nhanh tiến độ giải ngân vốn đầu tư công, không để thất thoát, lãng phí.</p>  
    """
    analysis, usage = await analyze_article(article)

    print(json.dumps(analysis.dict(), indent=2))
    print(json.dumps(usage.dict(), indent=2))

if __name__ == "__main__":
    await main()