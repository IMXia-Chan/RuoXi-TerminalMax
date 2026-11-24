package com.example.touchpad

/**
 * **连接器目录** —— 预置好的接口，一键装上就能用。
 *
 * 他 2026-11-06 在「API 宿主系统」那四项里全选了，这一块是第四项。
 *
 * ## 它和「自己加 API」「MCP」的分工
 *
 * | | 自己加 API | MCP | **目录** |
 * |---|---|---|---|
 * | 谁写声明 | 用户贴 openapi.json / curl | 服务器自己报 | **我们写好，放在这儿** |
 * | 用户要做什么 | 贴一份描述 | 填地址 | **点一下** |
 * | 适合什么 | 自己的私有接口 | 别家的 MCP 服务器 | **最常见的公开数据源** |
 *
 * ★ 目录里的东西**不自动装**（不像 [ApiStore] 的预置三条）。理由很实际：
 *   自动装 = 「她的能力」在用户不知情的情况下变多，而他删掉之后还可能被下一次
 *   升级补回来。目录的意义是**他挑**，不是我们塞。
 *
 * ★ 表里每一条都必须是**真实可用**的公开接口。写一条连不上的进来，比不写更糟 ——
 *   用户装完，她调一次失败，他会以为是这套东西坏了，而不是这一条坏了。
 *   所以每一条都留了「去哪拿 key」的说明，能不用 key 的就不用。
 */
data class Connector(
    val id: String,
    val name: String,
    val vendor: String,
    /** 一句人话：能干什么。**不写营销词。** */
    val note: String,
    /** 需不需要 key。需要的话，[keyHint] 写去哪拿。 */
    val needsKey: Boolean = false,
    val keyHint: String = "",
    /** 装好后那一档的地址。用户之后可以在面板里改。 */
    val baseUrl: String,
    val tools: List<ConnectorTool>,
)

/** 目录里的一项接口。字段和 [ApiToolDecl] 一一对应，安装时再绑到 purpose 上。 */
data class ConnectorTool(
    val name: String,
    val desc: String,
    val params: List<HandParam> = emptyList(),
    val required: List<String> = emptyList(),
    val exec: ApiExec,
    /** 可选：装好之后要提醒他的一句。空 = 不用提。 */
    val setupNote: String = "",
)

object ConnectorCatalog {

    val all: List<Connector> = listOf(
        // ★ 维基百科**不在目录里**，不是因为不需要，是国内连不上。
        //   2026-11-07 实测 `zh.wikipedia.org`、`zh.m.wikipedia.org`、
        //   `api.wikimedia.org` 三个地址全部连不通（curl 000），
        //   装上之后她每调一次就失败一次，看起来像整套接口坏了。
        //   目录里只放「装上就能用」的，这条等它有可用的地址再说。
        Connector(
            id = "geocode",
            name = "地名查坐标",
            vendor = "Open-Meteo",
            note = "把地名转成经纬度，配合「天气 · Open-Meteo」使用。不需要 key。",
            baseUrl = "https://geocoding-api.open-meteo.com",
            tools = listOf(
                ConnectorTool(
                    name = "find_place",
                    desc = "按地名查经纬度，返回前几条匹配的地点和所属国家、省份。" +
                        "可以直接用中文名，比如「上海」。查到的 latitude、" +
                        "longitude 可以直接填给天气工具。",
                    params = listOf(HandParam("name", "地名，比如「上海」「东京」")),
                    required = listOf("name"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v1/search",
                        query = listOf("name"),
                        fixedQuery = linkedMapOf(
                            // ★ language=zh：不给的话匹配到的是英文名，她会把
                            //   「Shanghai」和用户说的「上海」当成两回事。
                            "language" to "zh",
                            "count" to "5",
                            "format" to "json",
                        ),
                        pick = "results",
                        maxChars = 1500,
                    ),
                )
            ),
        ),
        Connector(
            id = "fx",
            name = "汇率",
            vendor = "open.er-api.com",
            note = "查某种货币对其他货币的当日汇率。不需要 key。",
            baseUrl = "https://open.er-api.com",
            tools = listOf(
                ConnectorTool(
                    name = "exchange_rate",
                    desc = "查基准货币的汇率，返回它对各种货币的比值和更新时间。",
                    params = listOf(HandParam("base", "基准货币的三字母代码，比如 USD、CNY")),
                    required = listOf("base"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v6/latest/{base}",
                        pick = "rates",
                        maxChars = 3000,
                    ),
                )
            ),
        ),
        Connector(
            id = "gh",
            name = "GitHub",
            vendor = "GitHub",
            note = "查公开仓库的简介、星数、语言、最近更新时间。不填 key 也能用，但每小时次数有限。",
            baseUrl = "https://api.github.com",
            tools = listOf(
                ConnectorTool(
                    name = "github_repo",
                    desc = "查一个公开仓库的基本信息。owner 是用户名或组织名，repo 是仓库名。",
                    params = listOf(
                        HandParam("owner", "用户名或组织名，比如 torvalds"),
                        HandParam("repo", "仓库名，比如 linux"),
                    ),
                    required = listOf("owner", "repo"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/repos/{owner}/{repo}",
                        // ★ 整份响应很大（几 KB 的字段名）。截到 1200 字，
                        //   她要的是「这个仓库是干什么的、多少星」，不是每一个 URL。
                        pick = "",
                        maxChars = 1200,
                    ),
                ),
                ConnectorTool(
                    name = "github_user",
                    desc = "查一个 GitHub 用户或组织：简介、公开仓库数、关注数、主页。",
                    params = listOf(HandParam("user", "用户名，比如 torvalds")),
                    required = listOf("user"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/users/{user}",
                        pick = "",
                        maxChars = 1000,
                    ),
                ),
                ConnectorTool(
                    name = "github_search",
                    desc = "按关键词搜索公开仓库，返回前几条的名称、简介和星数。",
                    params = listOf(HandParam("q", "关键词，比如「android bluetooth」")),
                    required = listOf("q"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/search/repositories",
                        fixedQuery = linkedMapOf("q" to "{q}", "per_page" to "5", "sort" to "stars"),
                        pick = "items",
                        maxChars = 2500,
                    ),
                )
            ),
        ),
        Connector(
            id = "country",
            name = "国家与地区资料",
            vendor = "restcountries.com",
            note = "查一个国家或地区的首都、人口、地区、货币。不需要 key。",
            baseUrl = "https://restcountries.com",
            tools = listOf(
                ConnectorTool(
                    name = "country_info",
                    desc = "按名称查一个国家或地区的基本资料，可以用中文名。",
                    params = listOf(HandParam("name", "国家或地区的名称，比如「日本」")),
                    required = listOf("name"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v3.1/name/{name}",
                        fixedQuery = linkedMapOf(
                            "fields" to "name,capital,population,region,subregion,currencies,languages",
                        ),
                        pick = "",
                        maxChars = 2000,
                    ),
                )
            ),
        ),
        Connector(
            id = "ipinfo",
            name = "IP 归属",
            vendor = "ipinfo.io",
            note = "查这个网络对外的 IP 和大致归属地。不需要 key，但免费额度较紧。",
            // ★ 原来是 ipapi.co。2026-11-07 实测它会对不带浏览器的请求回
            //   403 + Cloudflare 的「Just a moment...」人机验证页 ——
            //   我们自己拼的请求**永远**过不去，而错误看起来像「这个服务挂了」。
            baseUrl = "https://ipinfo.io",
            tools = listOf(
                // ★ 「不填就查本机」不做成同一个工具：路径里的 `{ip}` 没填时
                //   [ApiMath.fillTemplate] 按规矩**直接不发**（fail closed）。
                //   做成两个工具，比在路径里加一条「空参数特判」安全。
                ConnectorTool(
                    name = "my_ip",
                    desc = "查这个网络对外的 IP 和大致归属地。",
                    exec = ApiExec(method = "GET", path = "/json", pick = "", maxChars = 900),
                ),
                ConnectorTool(
                    name = "ip_lookup",
                    desc = "查指定 IP 的归属地、运营商和时区。",
                    params = listOf(HandParam("ip", "要查的 IP，比如 8.8.8.8")),
                    required = listOf("ip"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/{ip}/json",
                        pick = "",
                        maxChars = 900,
                    ),
                ),
            ),
        ),
        Connector(
            id = "qweather",
            name = "和风天气",
            vendor = "和风天气",
            note = "国内天气，按经纬度查实时天气和未来几天。需要 key。",
            needsKey = true,
            keyHint = "在 dev.qweather.com 注册后在控制台创建应用，复制 Key。",
            baseUrl = "https://devapi.qweather.com",
            tools = listOf(
                ConnectorTool(
                    name = "weather_now",
                    desc = "查某个位置的实时天气。经度在前、纬度在后，用逗号分开。",
                    params = listOf(HandParam("location", "经纬度，比如 115.89,28.68")),
                    required = listOf("location"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v7/weather/now",
                        query = listOf("location"),
                        fixedQuery = linkedMapOf("key" to "{key}"),
                        pick = "now",
                        maxChars = 900,
                    ),
                ),
                ConnectorTool(
                    name = "weather_days",
                    desc = "查某个位置未来几天的天气。days 只能填 3、7、10、15。",
                    params = listOf(
                        HandParam("location", "经纬度，比如 115.89,28.68"),
                        HandParam("days", "3、7、10 或 15"),
                    ),
                    required = listOf("location", "days"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v7/weather/{days}d",
                        query = listOf("location"),
                        fixedQuery = linkedMapOf("key" to "{key}"),
                        pick = "daily",
                        maxChars = 2500,
                    ),
                ),
            ),
        ),
        Connector(
            id = "openmeteo",
            name = "天气 · Open-Meteo",
            vendor = "Open-Meteo",
            note = "按经纬度查未来几天的天气，全球可用。不需要 key，可与「和风天气」并存。",
            baseUrl = "https://api.open-meteo.com",
            tools = listOf(
                ConnectorTool(
                    name = "weather_forecast",
                    desc = "查某个位置未来几天的逐日天气：最高最低气温、降水概率、天气代码。" +
                        "days 填 1 到 16。经纬度可以用「地名 + 经纬度」的方式先确定。",
                    params = listOf(
                        HandParam("latitude", "纬度，比如 28.68"),
                        HandParam("longitude", "经度，比如 115.89"),
                        HandParam("days", "查几天，1 到 16"),
                    ),
                    required = listOf("latitude", "longitude", "days"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v1/forecast",
                        query = listOf("latitude", "longitude"),
                        fixedQuery = linkedMapOf(
                            "daily" to "weather_code,temperature_2m_max,temperature_2m_min," +
                                "precipitation_probability_max",
                            // ★ timezone=auto：不给的话时间是 UTC，报「明天」会错一天。
                            "timezone" to "auto",
                            "forecast_days" to "{days}",
                        ),
                        pick = "daily",
                        maxChars = 2500,
                    ),
                ),
            ),
        ),
        Connector(
            id = "crossref",
            name = "论文检索 · Crossref",
            vendor = "Crossref",
            note = "按关键词查学术文献的标题、作者、DOI 和链接。不需要 key。",
            baseUrl = "https://api.crossref.org",
            tools = listOf(
                ConnectorTool(
                    name = "search_papers",
                    desc = "按关键词检索学术文献，返回前五条的标题、第一作者、年份和 DOI。",
                    params = listOf(HandParam("q", "关键词，比如「graphene battery」")),
                    required = listOf("q"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/works",
                        fixedQuery = linkedMapOf(
                            "query" to "{q}",
                            "rows" to "5",
                            // ★ 只要这几个字段：整份条目带几十个字段和链接，
                            //   全给会占满上下文，而她要的是「叫什么、谁写的、哪一年」。
                            "select" to "title,author,DOI,issued,URL",
                        ),
                        pick = "message.items",
                        maxChars = 2500,
                    ),
                ),
            ),
        ),
        Connector(
            id = "arxiv",
            name = "arXiv 论文",
            vendor = "arXiv.org",
            note = "检索物理、数学、计算机方向的预印本，返回标题、作者、摘要开头和链接。不需要 key。",
            baseUrl = "https://export.arxiv.org",
            tools = listOf(
                ConnectorTool(
                    name = "arxiv_search",
                    desc = "按关键词检索 arXiv 预印本，返回前几篇的标题、作者、日期和摘要。" +
                        "返回的是 XML 原文，读标题和 summary 字段即可。",
                    params = listOf(HandParam("q", "关键词，比如「diffusion model」")),
                    required = listOf("q"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/api/query",
                        fixedQuery = linkedMapOf(
                            "search_query" to "all:{q}",
                            "max_results" to "3",
                            "sortBy" to "relevance",
                        ),
                        pick = "",
                        // ★ XML 很占地方，三条正好够她挑，再多就只是噪音。
                        maxChars = 3500,
                    ),
                ),
            ),
        ),
        Connector(
            id = "holidays",
            name = "公共假日",
            vendor = "Nager.Date",
            note = "查一个国家的法定节假日，含中文名。中国大陆用 CN。不需要 key。",
            baseUrl = "https://date.nager.at",
            tools = listOf(
                ConnectorTool(
                    name = "public_holidays",
                    desc = "查某一年某个国家的法定节假日列表（日期、当地名称、是否全国性）。" +
                        "country 用两位国家代码，比如 CN、US、JP。",
                    params = listOf(
                        HandParam("year", "年份，比如 2026"),
                        HandParam("country", "两位国家代码，比如 CN"),
                    ),
                    required = listOf("year", "country"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/api/v3/PublicHolidays/{year}/{country}",
                        pick = "",
                        maxChars = 2500,
                    ),
                ),
            ),
        ),
        Connector(
            id = "translate",
            name = "翻译 · MyMemory",
            vendor = "MyMemory",
            note = "中英等语言互译，不需要 key，每天有免费额度。" +
                "启用后，要翻译的原文会发送给该服务方，请勿用于敏感内容。",
            baseUrl = "https://api.mymemory.translated.net",
            tools = listOf(
                ConnectorTool(
                    name = "translate",
                    desc = "翻译一段文字。from 与 to 用语言代码，比如 zh、en、ja。",
                    params = listOf(
                        HandParam("text", "要翻译的文字"),
                        HandParam("from", "源语言代码，比如 en"),
                        HandParam("to", "目标语言代码，比如 zh"),
                    ),
                    required = listOf("text", "from", "to"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/get",
                        fixedQuery = linkedMapOf("q" to "{text}", "langpair" to "{from}|{to}"),
                        pick = "responseData",
                        maxChars = 1200,
                    ),
                    setupNote = "这一项会把要翻译的原文发送给 MyMemory 的服务器。涉及隐私的内容不要用它。",
                ),
            ),
        ),
        // ★ 2026-11-07 加：国内做地理类问答，前面那两个「地名查坐标」只够把名字换成经纬度，
        //   真正能回答「附近有什么」「怎么走」的是高德。它要 key，但 key 是免费的。
        Connector(
            id = "amap",
            name = "高德地图",
            vendor = "高德开放平台",
            note = "查地点、查地址对应的经纬度、查两地的驾车路线。需要 key（个人开发者免费）。",
            needsKey = true,
            keyHint = "在 lbs.amap.com 注册开发者，控制台 → 应用管理 → 新建应用 → 添加「Web 服务」Key，复制那串 Key。",
            baseUrl = "https://restapi.amap.com",
            tools = listOf(
                ConnectorTool(
                    name = "amap_place",
                    desc = "按关键词找地点（餐厅、学校、医院、车站……），返回名称、地址、经纬度和电话。",
                    params = listOf(
                        HandParam("keywords", "要找什么，比如「上海西站」「打印店」"),
                        HandParam("city", "在哪个城市找，填城市名或行政区代码，比如「上海」"),
                    ),
                    required = listOf("keywords", "city"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v3/place/text",
                        query = listOf("keywords", "city"),
                        fixedQuery = linkedMapOf(
                            "key" to "{key}",
                            // ★ offset 封顶 10 条：一份 25 条的 POI 会有几千字，
                            //   而他要的几乎总是「最近的那几个」。
                            "offset" to "10",
                            "page" to "1",
                            "extensions" to "base",
                        ),
                        pick = "pois",
                        maxChars = 2500,
                    ),
                ),
                ConnectorTool(
                    name = "amap_geocode",
                    desc = "把一个地址换成经纬度。查天气那里要的经纬度就用它拿。",
                    params = listOf(HandParam("address", "完整地址，例如「某某市某某区某某路 88 号」")),
                    required = listOf("address"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v3/geocode/geo",
                        query = listOf("address"),
                        fixedQuery = linkedMapOf("key" to "{key}"),
                        pick = "geocodes",
                        maxChars = 1200,
                    ),
                ),
                ConnectorTool(
                    name = "amap_route",
                    desc = "查两地之间的驾车路线：距离（米）、预计耗时（秒）和主要道路。",
                    params = listOf(
                        HandParam("origin", "起点经纬度，经度在前：115.89,28.68"),
                        HandParam("destination", "终点经纬度，格式同上"),
                    ),
                    required = listOf("origin", "destination"),
                    exec = ApiExec(
                        method = "GET",
                        path = "/v3/direction/driving",
                        query = listOf("origin", "destination"),
                        fixedQuery = linkedMapOf("key" to "{key}", "extensions" to "base"),
                        pick = "route.paths",
                        maxChars = 1800,
                    ),
                ),
            ),
        ),
        // ★ 2026-11-07 加：Notion 是这一类「宿主系统」最常被问到的连接器 ——
        //   它自己有 REST 接口和 token，不需要 OAuth 那套跳转，装进来就能用。
        Connector(
            id = "notion",
            name = "Notion",
            vendor = "Notion",
            note = "在自己的 Notion 工作区里搜索页面，并读取某个数据库里的条目。" +
                "需要内部集成令牌（Integration Token）。",
            needsKey = true,
            keyHint = "notion.so → 设置 → 连接 → 开发你的集成 → 新建集成，复制内部集成令牌；" +
                "然后在要读的页面右上角「…」→ 连接 → 把这个集成加进去，否则它会说找不到任何东西。",
            baseUrl = "https://api.notion.com",
            tools = listOf(
                ConnectorTool(
                    name = "notion_search",
                    desc = "在自己已授权的 Notion 页面里按关键词搜索，返回标题、类型和链接。",
                    params = listOf(HandParam("query", "要搜的词，比如「周报」")),
                    required = listOf("query"),
                    exec = ApiExec(
                        method = "POST",
                        path = "/v1/search",
                        bodyParams = listOf("query"),
                        headers = linkedMapOf(
                            "Authorization" to "Bearer {key}",
                            // ★ 这个版本头是 Notion 的硬要求，不写直接 400 ——
                            //   而 400 的正文看起来像「你的请求写错了」，不像「少了个头」。
                            "Notion-Version" to "2022-06-28",
                            "Content-Type" to "application/json",
                        ),
                        maxChars = 3000,
                    ),
                    setupNote = "只读：这一项不会改你 Notion 里的任何东西。",
                ),
                ConnectorTool(
                    name = "notion_database",
                    desc = "读一个 Notion 数据库里的条目（最近 50 条）。" +
                        "database_id 是那个数据库地址里 32 位的那一段。",
                    params = listOf(
                        HandParam("database_id", "数据库 id，形如 32 个十六进制字符"),
                    ),
                    required = listOf("database_id"),
                    exec = ApiExec(
                        method = "POST",
                        path = "/v1/databases/{database_id}/query",
                        // ★ 不开放 page_size：api 那边是把参数当**字符串**放进 JSON body 的，
                        //   而 Notion 要的是数字 —— 传 "20" 会被 400 拒掉，
                        //   报错还长得像「你的请求格式不对」。让它用服务端默认（100 条）。
                        bodyParams = emptyList(),
                        headers = linkedMapOf(
                            "Authorization" to "Bearer {key}",
                            "Notion-Version" to "2022-06-28",
                            "Content-Type" to "application/json",
                        ),
                        pick = "results",
                        maxChars = 4000,
                    ),
                ),
            ),
        ),
    )

    fun find(id: String): Connector? = all.firstOrNull { it.id == id }

    /**
     * 目录项 → 待安装的草稿。
     *
     * ★ 一份 [ApiDraft] 而不是直接写盘：安装前要先给他看一眼「会多出哪几个工具」。
     *   和「自己加 API」走同一条确认路，理由也一样 —— 装错了不报错，
     *   只会在某天她调一个参数不对的接口。
     */
    fun draft(c: Connector): ApiDraft {
        val purpose = ApiPurpose(
            id = c.id,
            label = c.name,
            note = c.note,
            // ★ 地址先填好，key 留空 —— 他进去只填 key 那一个格子。
            profiles = listOf(
                ApiProfile(id = "default", label = c.vendor, baseUrl = c.baseUrl)
            ),
            activeId = "default",
        )
        val tools = c.tools.map { t ->
            ApiToolDecl(
                purpose = c.id,
                name = t.name,
                desc = t.desc,
                params = t.params,
                required = t.required,
                exec = t.exec,
            )
        }
        val notes = buildList {
            if (c.needsKey) add("需要 key：${c.keyHint}")
            c.tools.mapNotNull { it.setupNote }.forEach { add(it) }
        }
        return ApiDraft(purpose, tools, notes)
    }
}
