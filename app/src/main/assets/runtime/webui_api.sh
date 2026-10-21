#!/system/bin/sh
# WebUI API 脚本

# 加载公共函数库
. "/data/adb/byd_sign/lib.sh"

API_SCRIPT="$WORK_DIR/webui_api.sh"

# 获取模块目录（支持 KernelSU 和 Magisk）
get_moddir() {
    # KernelSU 模块路径
    if [ -d "/data/adb/modules/byd_auto_sign" ]; then
        echo "/data/adb/modules/byd_auto_sign"
    # Magisk 模块路径
    elif [ -d "/data/adb/magisk/byd_auto_sign" ]; then
        echo "/data/adb/magisk/byd_auto_sign"
    else
        echo ""
    fi
}

# 初始化 - 复制 API 脚本到工作目录
init_api() {
    local MODDIR=$(get_moddir)
    if [ -z "$MODDIR" ]; then
        return 1
    fi

    # 每次都强制复制，确保工作目录的脚本是最新版本
    # （之前用 -nt 判断在 Android 上跨目录不可靠，会导致更新后仍运行旧脚本）
    if [ -f "$MODDIR/bin/webui_api.sh" ]; then
        cp "$MODDIR/bin/webui_api.sh" "$API_SCRIPT" 2>/dev/null
        chmod 755 "$API_SCRIPT" 2>/dev/null
    fi
}

# 检查今日签到状态（所有端口都签到才返回 signed）
check_status() {
    local ports=$(get_code_api_ports)
    local all_signed=true
    for port in $ports; do
        port=$(echo "$port" | tr -d ' ')
        [ -z "$port" ] && continue
        if ! _is_signed_today "$port"; then
            all_signed=false
            break
        fi
    done
    if [ "$all_signed" = true ]; then
        echo "signed"
    else
        echo "not_signed"
    fi
}

# 获取最近一次签到日期（所有端口中最新的）
last_sign_date() {
    local latest=""
    if [ -d "$SIGN_STATUS_DIR" ]; then
        for f in "$SIGN_STATUS_DIR"/*.txt; do
            [ -f "$f" ] || continue
            local d=$(cat "$f" 2>/dev/null)
            if [ -n "$d" ] && [ "$d" \> "$latest" ]; then
                latest="$d"
            fi
        done
    fi
    # 向后兼容：旧文件
    if [ -z "$latest" ] && [ -f "$SIGN_STATUS_FILE" ]; then
        latest=$(cat "$SIGN_STATUS_FILE" 2>/dev/null)
    fi
    [ -n "$latest" ] && echo "$latest"
}

# 获取每个端口的签到状态详情（JSON 格式，含日期）
check_status_detail() {
    local ports=$(get_code_api_ports)
    local today=$(date '+%Y-%m-%d')
    local total=0
    local signed=0
    local port_details=""

    for port in $ports; do
        port=$(echo "$port" | tr -d ' ')
        [ -z "$port" ] && continue
        total=$((total + 1))

        local status="not_signed"
        local date=""
        local status_file="$SIGN_STATUS_DIR/${port}.txt"
        if [ -f "$status_file" ]; then
            local saved_date=$(cat "$status_file" 2>/dev/null)
            if [ "$saved_date" = "$today" ]; then
                status="signed"
                signed=$((signed + 1))
            fi
            date="$saved_date"
        fi

        if [ -n "$port_details" ]; then port_details="${port_details},"; fi
        port_details="${port_details}\"${port}\":{\"status\":\"${status}\",\"date\":\"${date}\"}"
    done

    if [ "$total" -eq 0 ]; then
        echo '{"ports":{},"total":0,"signed":0,"summary":"none"}'
        return 0
    fi

    local summary=""
    if [ "$signed" -eq "$total" ]; then
        summary="all_signed"
    elif [ "$signed" -eq 0 ]; then
        summary="none_signed"
    else
        summary="partial"
    fi

    printf '{"ports":{%s},"total":%d,"signed":%d,"summary":"%s"}\n' \
        "$port_details" "$total" "$signed" "$summary"
}

# 执行签到（后台运行，避免阻塞 WebUI）
do_sign_async() {
    if [ ! -f "$WORK_DIR/byd_sign.sh" ]; then
        echo "error:签到脚本不存在"
        return 1
    fi

    # 如果已有签到进程在运行，返回进行中
    if [ -f "$WORK_DIR/.sign_pid" ] && kill -0 "$(cat "$WORK_DIR/.sign_pid")" 2>/dev/null; then
        echo "running"
        return 0
    fi

    # 后台执行签到，输出到结果文件
    nohup "$WORK_DIR/byd_sign.sh" "nonauto" > "$WORK_DIR/.sign_result" 2>&1 &
    echo $! > "$WORK_DIR/.sign_pid"
    echo "running"
}

# 查询异步签到结果
check_sign_result() {
    if [ ! -f "$WORK_DIR/.sign_pid" ]; then
        echo "not_started"
        return 0
    fi

    local pid=$(cat "$WORK_DIR/.sign_pid" 2>/dev/null)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        echo "running"
    else
        # 进程已结束，返回结果
        if [ -f "$WORK_DIR/.sign_result" ]; then
            cat "$WORK_DIR/.sign_result"
        else
            echo "unknown"
        fi
        # 清理 pid 文件
        rm -f "$WORK_DIR/.sign_pid"
    fi
}

# 执行签到（同步，保留兼容）
do_sign() {
    if [ ! -f "$WORK_DIR/byd_sign.sh" ]; then
        echo "错误: 签到脚本不存在"
        return 1
    fi

    "$WORK_DIR/byd_sign.sh" "nonauto" 2>&1
}

# 列出日志文件（用 | 分隔，避免换行丢失）
list_logs() {
    if [ ! -d "$LOG_DIR" ]; then
        return 0
    fi

    local first=1
    for f in "$LOG_DIR"/*.log; do
        if [ -f "$f" ]; then
            if [ "$first" -eq 1 ]; then
                printf "%s" "$(basename "$f")"
                first=0
            else
                printf "|%s" "$(basename "$f")"
            fi
        fi
    done
}

# 读取日志内容（限制行数避免阻塞）
read_log() {
    local log_file="$1"
    local max_lines="${2:-200}"
    local log_path="$LOG_DIR/$log_file"

    if [ -z "$log_file" ]; then
        echo "错误: 未指定日志文件"
        return 1
    fi

    if [ ! -f "$log_path" ]; then
        echo "错误: 日志文件不存在"
        return 1
    fi

    tail -n "$max_lines" "$log_path"
}

# 读取邮件配置
read_email_config() {
    if [ ! -f "$EMAIL_CONF" ]; then
        echo '{"EMAIL_ENABLED":"true","EMAIL_FROM":"","EMAIL_TO":"","EMAIL_AUTH":"","SMTP_SERVER":"","SMTP_PORT":"","WEBHOOK_URL":"","WEBHOOK_TYPE":"","DINGTALK_SECRET":"","TELEGRAM_CHAT_ID":"","PUSHPLUS_TOKEN":"","PUSHPLUS_CHANNEL":"","PUSHPLUS_OPTION":""}'
        return 0
    fi

    # 读取配置并转换为 JSON，去掉引号
    local email_enabled=$(grep "^EMAIL_ENABLED=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local email_from=$(grep "^EMAIL_FROM=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local email_to=$(grep "^EMAIL_TO=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local email_auth=$(grep "^EMAIL_AUTH=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local smtp_server=$(grep "^SMTP_SERVER=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local smtp_port=$(grep "^SMTP_PORT=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local webhook_url=$(grep "^WEBHOOK_URL=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local webhook_type=$(grep "^WEBHOOK_TYPE=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local dingtalk_secret=$(grep "^DINGTALK_SECRET=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local telegram_chat_id=$(grep "^TELEGRAM_CHAT_ID=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local pushplus_token=$(grep "^PUSHPLUS_TOKEN=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local pushplus_channel=$(grep "^PUSHPLUS_CHANNEL=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')
    local pushplus_option=$(grep "^PUSHPLUS_OPTION=" "$EMAIL_CONF" 2>/dev/null | cut -d'=' -f2- | tr -d '"')

    # 默认值
    [ -z "$email_enabled" ] && email_enabled="true"

    # 转义特殊字符（JSON 安全）
    email_enabled=$(echo "$email_enabled" | sed 's/\\/\\\\/g; s/"/\\"/g')
    email_from=$(echo "$email_from" | sed 's/\\/\\\\/g; s/"/\\"/g')
    email_to=$(echo "$email_to" | sed 's/\\/\\\\/g; s/"/\\"/g')
    email_auth=$(echo "$email_auth" | sed 's/\\/\\\\/g; s/"/\\"/g')
    smtp_server=$(echo "$smtp_server" | sed 's/\\/\\\\/g; s/"/\\"/g')
    smtp_port=$(echo "$smtp_port" | sed 's/\\/\\\\/g; s/"/\\"/g')
    webhook_url=$(echo "$webhook_url" | sed 's/\\/\\\\/g; s/"/\\"/g')
    webhook_type=$(echo "$webhook_type" | sed 's/\\/\\\\/g; s/"/\\"/g')
    dingtalk_secret=$(echo "$dingtalk_secret" | sed 's/\\/\\\\/g; s/"/\\"/g')
    telegram_chat_id=$(echo "$telegram_chat_id" | sed 's/\\/\\\\/g; s/"/\\"/g')
    pushplus_token=$(echo "$pushplus_token" | sed 's/\\/\\\\/g; s/"/\\"/g')
    pushplus_channel=$(echo "$pushplus_channel" | sed 's/\\/\\\\/g; s/"/\\"/g')
    pushplus_option=$(echo "$pushplus_option" | sed 's/\\/\\\\/g; s/"/\\"/g')

    # 构建 JSON
    printf '{"EMAIL_ENABLED":"%s","EMAIL_FROM":"%s","EMAIL_TO":"%s","EMAIL_AUTH":"%s","SMTP_SERVER":"%s","SMTP_PORT":"%s","WEBHOOK_URL":"%s","WEBHOOK_TYPE":"%s","DINGTALK_SECRET":"%s","TELEGRAM_CHAT_ID":"%s","PUSHPLUS_TOKEN":"%s","PUSHPLUS_CHANNEL":"%s","PUSHPLUS_OPTION":"%s"}' \
        "$email_enabled" "$email_from" "$email_to" "$email_auth" "$smtp_server" "$smtp_port" "$webhook_url" "$webhook_type" "$dingtalk_secret" "$telegram_chat_id" "$pushplus_token" "$pushplus_channel" "$pushplus_option"
}

# 保存邮件配置（key=value 输入；WebUI 对外仍使用 JSON 返回）
save_email_config() {
    local config_kv="$1"
    local conf_dir="${EMAIL_CONF%/*}"
    local tmp_file="${EMAIL_CONF}.tmp.$$"
    local backup_file="${EMAIL_CONF}.bak"
    local email_enabled=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "EMAIL_ENABLED" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }' | tr -d '[:space:]\r"')
    local email_from=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "EMAIL_FROM" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local email_to=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "EMAIL_TO" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local email_auth=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "EMAIL_AUTH" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local smtp_server=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "SMTP_SERVER" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local smtp_port=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "SMTP_PORT" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local webhook_url=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "WEBHOOK_URL" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local webhook_type=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "WEBHOOK_TYPE" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local dingtalk_secret=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "DINGTALK_SECRET" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local telegram_chat_id=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "TELEGRAM_CHAT_ID" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local pushplus_token=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "PUSHPLUS_TOKEN" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local pushplus_channel=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "PUSHPLUS_CHANNEL" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')
    local pushplus_option=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "PUSHPLUS_OPTION" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }')

    if [ -z "$config_kv" ] || [ -z "$email_enabled" ]; then
        echo "错误: 配置为空或缺少 EMAIL_ENABLED"
        return 1
    fi
    if [ "$email_enabled" = "true" ] && { [ -z "$email_from" ] || [ -z "$email_to" ] || [ -z "$email_auth" ] || [ -z "$smtp_server" ] || [ -z "$smtp_port" ]; }; then
        echo "错误: 邮件启用时配置不完整，拒绝覆盖已有配置"
        return 1
    fi
    # 配置通过命令参数传入；逐项 awk 解析已按行处理，不能用 grep '[\\r\\n]' 检查，
    # 该写法在 Android grep 中会误匹配普通字母并拒绝合法邮箱/授权码。
    mkdir -p "$conf_dir"
    {
        echo "# 通知配置"
        echo "EMAIL_ENABLED=\"$email_enabled\""
        echo "EMAIL_FROM=\"$email_from\""
        echo "EMAIL_TO=\"$email_to\""
        echo "EMAIL_AUTH=\"$email_auth\""
        echo "SMTP_SERVER=\"$smtp_server\""
        echo "SMTP_PORT=\"$smtp_port\""
        echo "WEBHOOK_URL=\"$webhook_url\""
        echo "WEBHOOK_TYPE=\"$webhook_type\""
        echo "DINGTALK_SECRET=\"$dingtalk_secret\""
        echo "TELEGRAM_CHAT_ID=\"$telegram_chat_id\""
        echo "PUSHPLUS_TOKEN=\"$pushplus_token\""
        echo "PUSHPLUS_CHANNEL=\"$pushplus_channel\""
        echo "PUSHPLUS_OPTION=\"$pushplus_option\""
    } > "$tmp_file" || { rm -f "$tmp_file"; return 1; }
    chmod 600 "$tmp_file"
    [ -f "$EMAIL_CONF" ] && cp "$EMAIL_CONF" "$backup_file" && chmod 600 "$backup_file"
    mv -f "$tmp_file" "$EMAIL_CONF"
    chmod 600 "$EMAIL_CONF"
    echo "配置已保存"
}

# 从文件保存邮件配置（避免引号问题）
save_email_config_file() {
    local tmp_file="$1"

    if [ -z "$tmp_file" ] || [ ! -f "$tmp_file" ]; then
        echo "错误: 配置文件不存在"
        return 1
    fi

    local config_json=$(cat "$tmp_file")
    save_email_config "$config_json"

    # 清理临时文件
    rm -f "$tmp_file" 2>/dev/null
}

# 从 base64 编码保存邮件配置
save_email_config_base64() {
    local config_base64="$1"

    if [ -z "$config_base64" ]; then
        echo "错误: 配置为空"
        return 1
    fi

    # 解码 base64；当前格式为 key=value，兼容旧版 JSON 请求。
    local config_data=$(echo "$config_base64" | base64 -d 2>/dev/null)
    if [ -z "$config_data" ]; then
        echo "错误: base64 解码失败"
        return 1
    fi
    if echo "$config_data" | grep -q '^[[:space:]]*{'; then
        local config_kv=""
        for key in EMAIL_ENABLED EMAIL_FROM EMAIL_TO EMAIL_AUTH SMTP_SERVER SMTP_PORT WEBHOOK_URL WEBHOOK_TYPE DINGTALK_SECRET TELEGRAM_CHAT_ID PUSHPLUS_TOKEN PUSHPLUS_CHANNEL PUSHPLUS_OPTION; do
            local value=$(echo "$config_data" | sed -n "s/.*\"$key\":\"\([^\"]*\)\".*/\1/p")
            config_kv="${config_kv}${key}=${value}\n"
        done
        config_data=$(printf '%b' "$config_kv")
    fi
    save_email_config "$config_data"
}

# 删除日志文件
delete_log() {
    local log_file="$1"
    local log_path="$LOG_DIR/$log_file"

    if [ -z "$log_file" ]; then
        echo "错误: 未指定日志文件"
        return 1
    fi

    if [ ! -f "$log_path" ]; then
        echo "错误: 日志文件不存在"
        return 1
    fi

    # 删除日志文件
    rm -f "$log_path"
    
    if [ -f "$log_path" ]; then
        echo "错误: 删除失败"
        return 1
    else
        echo "删除成功"
        return 0
    fi
}

# 删除所有日志文件
delete_all_logs() {
    if [ ! -d "$LOG_DIR" ]; then
        echo "日志目录不存在"
        return 1
    fi

    local count=0
    
    # 遍历目录中的所有.log文件
    for f in "$LOG_DIR"/*.log; do
        # 检查文件是否存在（避免通配符未匹配时的错误）
        if [ -f "$f" ]; then
            rm -f "$f"
            if [ ! -f "$f" ]; then
                count=$((count + 1))
            fi
        fi
    done

    if [ $count -eq 0 ]; then
        echo "没有找到日志文件"
    else
        echo "已删除 $count 个日志文件"
    fi
    return 0
}

# 获取模块版本号
get_version() {
    local MODDIR=$(get_moddir)
    if [ -z "$MODDIR" ]; then
        echo "unknown"
        return 1
    fi

    local version=$(grep '^version=' "$MODDIR/module.prop" 2>/dev/null | cut -d'=' -f2)
    if [ -n "$version" ]; then
        echo "$version"
    else
        echo "unknown"
    fi
}

# 读取定时任务配置
# 返回 JSON:
#   简单模式: {"mode":"simple","enabled":true,"minute":1,"times":["00:01","07:01"],"cron_expr":"1 0,7 * * *","raw":"...","cron_running":true}
#   自定义模式: {"mode":"custom","enabled":true,"cron_expr":"*/30 * * * *","raw":"...","cron_running":true}
read_cron_config() {
    local cron_file="$WORK_DIR/cron.d/root"
    local enabled="true"
    local minute="1"
    local times=""
    local cron_expr="1 0 * * *"
    local mode="simple"
    local raw=""

    # 检查 crond 进程是否在运行
    find_busybox
    local cron_running="false"
    if $BUSYBOX pgrep -f "crond.*${WORK_DIR}/cron\.d" >/dev/null 2>&1; then
        cron_running="true"
    fi

    if [ -f "$cron_file" ]; then
        raw=$(cat "$cron_file" 2>/dev/null)
        local line=$(echo "$raw" | head -n1)

        # 检测是否被禁用（行首为 #）
        if echo "$line" | grep -q '^#'; then
            enabled="false"
            line=$(echo "$line" | sed 's/^# *//')
        fi

        # 提取前 5 个字段（minute hour day month week）
        local f1=$(echo "$line" | awk '{print $1}')
        local f2=$(echo "$line" | awk '{print $2}')
        local f3=$(echo "$line" | awk '{print $3}')
        local f4=$(echo "$line" | awk '{print $4}')
        local f5=$(echo "$line" | awk '{print $5}')

        # 空文件或无有效内容时返回默认值
        if [ -z "$f1" ]; then
            cron_expr="1 0 * * *"
            printf '{"mode":"%s","enabled":%s,"minute":%s,"times":[%s],"cron_expr":"%s","raw":"%s","cron_running":%s}\n' \
                "$mode" "$enabled" "$minute" "$times" "1 0 * * *" "" "$cron_running"
            return 0
        fi

        cron_expr="${f1} ${f2} ${f3} ${f4} ${f5}"

        # 判断是否为简单模式：
        # - minute 是纯数字（0-59）
        # - day/month/week 都是 *
        # - hour 是 * 或 逗号分隔的纯数字列表（可能含范围，但范围展开后仍是简单列表）
        local is_simple=1
        # 校验 minute：必须是纯数字且 0-59
        if ! echo "$f1" | grep -q '^[0-9][0-9]*$'; then
            is_simple=0
        elif [ "$f1" -lt 0 ] 2>/dev/null || [ "$f1" -gt 59 ] 2>/dev/null; then
            is_simple=0
        fi
        # 校验 day/month/week 必须是 *
        if [ "$f3" != "*" ] || [ "$f4" != "*" ] || [ "$f5" != "*" ]; then
            is_simple=0
        fi
        # 校验 hour：允许 * 或 逗号分隔的数字（可含 - 范围）
        # 使用基本正则（BRE）以兼容 Android toybox grep
        if [ "$f2" != "*" ]; then
            if ! echo "$f2" | grep -q '^[0-9]\{1,\}\(-[0-9]\{1,\}\)\{0,1\}\(,[0-9]\{1,\}\(-[0-9]\{1,\}\)\{0,1\}\)*$'; then
                is_simple=0
            fi
        fi

        if [ "$is_simple" -eq 1 ]; then
            mode="simple"
            # 去除前导零避免后续解析问题
            minute=$(echo "$f1" | sed 's/^00*//')
            [ -z "$minute" ] && minute="0"
            # 解析 hour 为 times 列表
            if [ "$f2" = "*" ]; then
                times=""
            else
                local hour_list=$(echo "$f2" | tr ',' '\n')
                local times_arr=""
                for h in $hour_list; do
                    # 去除前导零避免 printf 解析为八进制
                    local h_norm=$(echo "$h" | sed 's/^00*//')
                    [ -z "$h_norm" ] && h_norm="0"
                    if echo "$h_norm" | grep -q '-'; then
                        local start=$(echo "$h_norm" | cut -d'-' -f1)
                        local end=$(echo "$h_norm" | cut -d'-' -f2)
                        local i=$start
                        while [ "$i" -le "$end" ] 2>/dev/null; do
                            local hh=$(printf "%02d" "$i" 2>/dev/null || echo "$i")
                            local mm=$(printf "%02d" "$minute" 2>/dev/null || echo "$minute")
                            if [ -z "$times_arr" ]; then
                                times_arr="\"${hh}:${mm}\""
                            else
                                times_arr="${times_arr},\"${hh}:${mm}\""
                            fi
                            i=$((i + 1))
                        done
                    else
                        local hh=$(printf "%02d" "$h_norm" 2>/dev/null || echo "$h_norm")
                        local mm=$(printf "%02d" "$minute" 2>/dev/null || echo "$minute")
                        if [ -z "$times_arr" ]; then
                            times_arr="\"${hh}:${mm}\""
                        else
                            times_arr="${times_arr},\"${hh}:${mm}\""
                        fi
                    fi
                done
                times="$times_arr"
            fi
        else
            mode="custom"
        fi
    fi

    # 转义 raw
    local raw_escaped=$(echo "$raw" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ' | sed 's/  */ /g' | sed 's/^ *//;s/ *$//')

    # 转义 cron_expr
    local cron_expr_escaped=$(echo "$cron_expr" | sed 's/\\/\\\\/g; s/"/\\"/g')

    printf '{"mode":"%s","enabled":%s,"minute":%s,"times":[%s],"cron_expr":"%s","raw":"%s","cron_running":%s}' \
        "$mode" "$enabled" "$minute" "$times" "$cron_expr_escaped" "$raw_escaped" "$cron_running"
}

# 保存定时任务配置（base64 输入）
# 输入 JSON:
#   简单模式: {"mode":"simple","enabled":true,"minute":1,"times":["00:01","07:01"]}
#   自定义模式: {"mode":"custom","enabled":true,"cron_expr":"*/30 * * * *"}
save_cron_config_base64() {
    local config_base64="$1"

    if [ -z "$config_base64" ]; then
        echo "错误: 配置为空"
        return 1
    fi

    # 解码 base64
    local config_json=$(echo "$config_base64" | base64 -d 2>/dev/null)

    if [ -z "$config_json" ]; then
        echo "错误: base64 解码失败"
        return 1
    fi

    save_cron_config "$config_json"
}

# 保存定时任务配置（JSON 输入）
save_cron_config() {
    local config_json="$1"

    if [ -z "$config_json" ]; then
        echo "错误: 配置为空"
        return 1
    fi

    # 解析 mode 字段
    local mode=$(echo "$config_json" | sed 's/.*"mode":"\([^"]*\)".*/\1/')
    [ -z "$mode" ] || [ "$mode" = "$config_json" ] && mode="simple"

    local enabled=$(echo "$config_json" | sed 's/.*"enabled":\([^,}]*\).*/\1/' | tr -d ' ')
    [ -z "$enabled" ] && enabled="true"

    local cron_line=""
    local has_cron=0

    if [ "$mode" = "custom" ]; then
        # 自定义模式：直接使用 cron_expr
        local cron_expr=$(echo "$config_json" | sed 's/.*"cron_expr":"\([^"]*\)".*/\1/')
        if [ -z "$cron_expr" ] || [ "$cron_expr" = "$config_json" ]; then
            echo "错误: cron 表达式为空"
            return 1
        fi

        # 校验 cron_expr 格式（至少 5 段，用空格分隔）
        local field_count=$(echo "$cron_expr" | awk '{print NF}')
        if [ "$field_count" -lt 5 ] 2>/dev/null; then
            echo "错误: cron 表达式格式无效（应为 5 段: 分 时 日 月 周）"
            return 1
        fi

        # 只取前 5 段，忽略多余内容
        local f1=$(echo "$cron_expr" | awk '{print $1}')
        local f2=$(echo "$cron_expr" | awk '{print $2}')
        local f3=$(echo "$cron_expr" | awk '{print $3}')
        local f4=$(echo "$cron_expr" | awk '{print $4}')
        local f5=$(echo "$cron_expr" | awk '{print $5}')
        cron_line="${f1} ${f2} ${f3} ${f4} ${f5} $WORK_DIR/byd_sign.sh > /dev/null 2>&1"
        has_cron=1
    else
        # 简单模式
        local minute=$(echo "$config_json" | sed 's/.*"minute":\([0-9]*\).*/\1/')
        local times_str=$(echo "$config_json" | sed 's/.*"times":\[\([^]]*\)\].*/\1/')

        [ -z "$minute" ] && minute="1"

        # 校验 minute（0-59）
        if ! echo "$minute" | grep -q '^[0-9][0-9]*$'; then
            echo "错误: 分钟无效（应为 0-59）"
            return 1
        elif [ "$minute" -lt 0 ] 2>/dev/null || [ "$minute" -gt 59 ] 2>/dev/null; then
            echo "错误: 分钟无效（应为 0-59）"
            return 1
        fi

        # 解析 times 列表，提取小时
        local hours=""
        local has_times=0
        if [ -n "$times_str" ] && [ "$times_str" != "$config_json" ]; then
            for t in $(echo "$times_str" | tr ',' '\n' | tr -d '"' | tr -d ' '); do
                if [ -n "$t" ]; then
                    local hh=$(echo "$t" | cut -d':' -f1)
                    # 去除前导零避免八进制解析问题
                    hh=$(echo "$hh" | sed 's/^00*//')
                    [ -z "$hh" ] && hh="0"
                    if echo "$hh" | grep -q '^[0-9][0-9]*$' && [ "$hh" -ge 0 ] 2>/dev/null && [ "$hh" -le 23 ] 2>/dev/null; then
                        has_times=1
                        if [ -z "$hours" ]; then
                            hours="$hh"
                        else
                            hours="${hours},${hh}"
                        fi
                    fi
                fi
            done
        fi

        # 去重并排序小时
        if [ "$has_times" -eq 1 ]; then
            hours=$(echo "$hours" | tr ',' '\n' | sort -n | uniq | tr '\n' ',' | sed 's/,$//')
        fi

        if [ "$has_times" -eq 0 ] || [ -z "$hours" ]; then
            # 没有有效时间，禁用任务
            cron_line="# ${minute} * * * * $WORK_DIR/byd_sign.sh > /dev/null 2>&1"
            enabled="false"
        else
            cron_line="${minute} ${hours} * * * $WORK_DIR/byd_sign.sh > /dev/null 2>&1"
            has_cron=1
        fi
    fi

    # 如果没有有效 cron 且未设置禁用行，构建一个禁用行
    if [ "$has_cron" -eq 0 ] && [ -z "$cron_line" ]; then
        cron_line="# 1 * * * * $WORK_DIR/byd_sign.sh > /dev/null 2>&1"
        enabled="false"
    fi

    # 如果禁用，行首加 #
    if [ "$enabled" = "false" ] && ! echo "$cron_line" | grep -q '^#'; then
        cron_line="# ${cron_line}"
    fi

    # 确保目录存在
    mkdir -p "$WORK_DIR/cron.d"

    # 写入 cron 文件
    echo "$cron_line" > "$WORK_DIR/cron.d/root"
    chmod 644 "$WORK_DIR/cron.d/root" 2>/dev/null

    # 重启 crond（复用 lib.sh 中的 restart_crond）
    restart_crond

    if [ "$enabled" = "true" ]; then
        echo "配置已保存并生效"
    else
        echo "配置已保存（任务已禁用）"
    fi
}

# 测试通知发送
test_notification() {
    echo "=== 开始测试通知 ==="
    echo ""

    # 读取当前配置
    echo "[配置] 读取配置文件: $EMAIL_CONF"
    if [ -f "$EMAIL_CONF" ]; then
        . "$EMAIL_CONF"
        echo "[配置] 配置文件已加载"
    else
        echo "[配置] 配置文件不存在，使用默认值"
    fi

    local has_email=0
    local has_webhook=0

    # 检查邮件配置
    echo ""
    echo "[邮件] 检查邮件配置..."
    echo "[邮件] EMAIL_ENABLED: ${EMAIL_ENABLED:-未设置}"
    echo "[邮件] EMAIL_FROM: ${EMAIL_FROM:-未设置}"
    echo "[邮件] EMAIL_TO: ${EMAIL_TO:-未设置}"
    echo "[邮件] SMTP_SERVER: ${SMTP_SERVER:-未设置}"
    echo "[邮件] SMTP_PORT: ${SMTP_PORT:-未设置}"

    if [ "$EMAIL_ENABLED" = "true" ] && [ -n "$EMAIL_FROM" ] && [ -n "$EMAIL_TO" ] && [ -n "$EMAIL_AUTH" ]; then
        has_email=1
        echo "[邮件] 邮件配置完整，将发送测试邮件"
    else
        echo "[邮件] 邮件未启用或配置不完整，跳过"
    fi

    # 检查 Webhook 配置
    echo ""
    echo "[Webhook] 检查 Webhook 配置..."
    echo "[Webhook] WEBHOOK_TYPE: ${WEBHOOK_TYPE:-未设置}"
    echo "[Webhook] WEBHOOK_URL: ${WEBHOOK_URL:-未设置}"
    echo "[Webhook] DINGTALK_SECRET: ${DINGTALK_SECRET:+已设置}"
    echo "[Webhook] TELEGRAM_CHAT_ID: ${TELEGRAM_CHAT_ID:-未设置}"
    echo "[Webhook] PUSHPLUS_TOKEN: ${PUSHPLUS_TOKEN:+已设置}"
    echo "[Webhook] PUSHPLUS_CHANNEL: ${PUSHPLUS_CHANNEL:-默认渠道}"
    echo "[Webhook] PUSHPLUS_OPTION: ${PUSHPLUS_OPTION:-未设置}"

    if [ -n "$WEBHOOK_TYPE" ] && { [ -n "$WEBHOOK_URL" ] || [ "$WEBHOOK_TYPE" = "pushplus" ]; }; then
        has_webhook=1
        echo "[Webhook] Webhook 配置完整，将发送测试通知"
    else
        echo "[Webhook] Webhook 未完整配置，跳过"
    fi

    if [ "$has_email" -eq 0 ] && [ "$has_webhook" -eq 0 ]; then
        echo ""
        echo "错误: 未配置任何通知方式"
        return 1
    fi

    local title="[测试] 比亚迪签到通知测试"
    local message="这是一条测试通知，如果您收到此消息，说明通知配置正常。测试时间: $(date '+%Y-%m-%d %H:%M:%S')"

    # 测试邮件（复用公共 send_email 函数）
    if [ "$has_email" -eq 1 ]; then
        echo ""
        echo "[邮件] 正在发送测试邮件..."
        if send_email "$title" "$message"; then
            echo "[邮件] 发送成功"
        else
            echo "[邮件] 发送失败（详情见日志文件）"
        fi
    fi

    # 测试 Webhook（复用公共 send_webhook 函数）
    if [ "$has_webhook" -eq 1 ]; then
        echo ""
        echo "[Webhook] 正在发送测试通知..."
        echo "[Webhook] 类型: $WEBHOOK_TYPE"
        echo "[Webhook] 请求 URL: ${WEBHOOK_URL%%\?*}..."

        if send_webhook "$title" "$message"; then
            echo "[Webhook] 发送成功"
        else
            echo "[Webhook] 发送失败（详情见日志文件）"
        fi
    fi

    echo ""
    echo "=== 测试完成 ==="
}

# 测试获取 code（支持多端口）
test_get_code() {
    local APPID_P1="wxf62054"
    local APPID_P2="ec313d6f53"
    local APPID="${APPID_P1}${APPID_P2}"
    local curl_bin=$(get_curl_bin)

    # 读取端口配置
    local ports=$(get_code_api_ports)

    echo "=== 开始测试获取 Code (共 $(echo "$ports" | wc -w) 个端口) ==="
    echo ""

    local all_ok=true

    for port in $ports; do
        port=$(echo "$port" | tr -d ' ')
        if [ -z "$port" ]; then
            continue
        fi
        echo "[端口 ${port}] 测试中..."

        # 1. 先检测接口是否可达
        local api_host="127.0.0.1"
        local connect_result=$($curl_bin -s --connect-timeout 5 --max-time 5 -o /dev/null -w "%{http_code}" "http://${api_host}:${port}/" 2>/dev/null)
        if [ -z "$connect_result" ] || [ "$connect_result" = "000" ]; then
            echo "[端口 ${port}] 本地接口服务不可达 (${api_host}:${port})，请确保 wxcode 模块已启动"
            all_ok=false
            continue
        fi

        # 2. 尝试获取 code
        # 统一调用 lib.sh 中的 get_code_from_api。
        # WebUI 测试保留原超时设置：--connect-timeout 10 --max-time 15。
        local response_file="$WORK_DIR/.code_test_response_${port}_$$"
        local code=""
        code=$(get_code_from_api "$port" "$APPID" "--connect-timeout 10 --max-time 15" "$response_file")
        local ret=$?
        local response=$(cat "$response_file" 2>/dev/null)
        rm -f "$response_file" 2>/dev/null

        if [ -z "$response" ]; then
            echo "[端口 ${port}] 接口返回为空，请检查本地接口服务"
            all_ok=false
            continue
        fi

        if [ $ret -eq 0 ] && [ -n "$code" ] && [ "$code" != "" ]; then
            echo "[端口 ${port}] 成功: 接口可达，code 获取成功 (${code:0:10}...)"
        else
            echo "[端口 ${port}] 接口可达，但获取 code 失败，响应: $response"
            all_ok=false
        fi
        echo ""
    done

    echo "=== 测试完成 ==="
    if [ "$all_ok" = true ]; then
        return 0
    else
        return 1
    fi
}

# === CODE_API 端口配置管理 ===

# 读取 CODE_API 端口配置；文件内部使用 key=value，对外仍返回 JSON 兼容 WebUI。
read_code_api_config() {
    local ports="8088"
    local force_wake="false"
    if [ -f "$CODE_API_CONF" ]; then
        ports=$(awk -F= '$1 == "ports" { value=$0; sub(/^[^=]*=/, "", value); gsub(/[[:space:]]*\r$/, "", value); print value; exit }' "$CODE_API_CONF" 2>/dev/null)
        force_wake=$(awk -F= '$1 == "force_foreground_wake" { value=$0; sub(/^[^=]*=/, "", value); gsub(/[[:space:]]*\r$/, "", value); print value; exit }' "$CODE_API_CONF" 2>/dev/null)
    fi
    [ -z "$ports" ] && ports="8088"
    [ "$force_wake" = "true" ] || force_wake="false"

    local ports_json=""
    for port in $(echo "$ports" | tr ',' ' '); do
        echo "$port" | grep -q '^[0-9][0-9]*$' || continue
        [ -n "$ports_json" ] && ports_json="${ports_json},"
        ports_json="${ports_json}\"${port}\""
    done
    [ -z "$ports_json" ] && ports_json='"8088"'
    printf '{"ports":[%s],"force_foreground_wake":%s}\n' "$ports_json" "$force_wake"
}

# 保存 CODE API 端口配置（key=value 输入）
save_code_api_config() {
    local config_kv="$1"
    local ports_str=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "ports" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }' | tr ',' ' ')
    local force_wake=$(printf '%s\n' "$config_kv" | awk -F= '$1 == "force_foreground_wake" { value=$0; sub(/^[^=]*=/, "", value); print value; exit }' | tr -d '[:space:]\r')

    if [ -z "$ports_str" ]; then
        echo "错误: 端口列表为空"
        return 1
    fi
    for port in $ports_str; do
        if ! echo "$port" | grep -q '^[0-9][0-9]*$' || [ "$port" -lt 1 ] 2>/dev/null || [ "$port" -gt 65535 ] 2>/dev/null; then
            echo "错误: 端口无效: $port"
            return 1
        fi
    done
    [ "$force_wake" = "true" ] || force_wake="false"

    mkdir -p "${CODE_API_CONF%/*}"
    {
        echo "ports=$(echo "$ports_str" | tr ' ' ',')"
        echo "force_foreground_wake=$force_wake"
    } > "$CODE_API_CONF"
    chmod 600 "$CODE_API_CONF"
    echo "配置已保存"
}

# 从 base64 编码保存 CODE_API key=value 配置
save_code_api_config_base64() {
    local config_base64="$1"

    if [ -z "$config_base64" ]; then
        echo "错误: 配置为空"
        return 1
    fi

    local config_kv=$(echo "$config_base64" | base64 -d 2>/dev/null)
    if [ -z "$config_kv" ]; then
        echo "错误: base64 解码失败"
        return 1
    fi

    save_code_api_config "$config_kv"
}

# 主入口
case "$1" in
    init)
        init_api
        ;;
    check_status)
        check_status
        ;;
    check_status_detail)
        check_status_detail
        ;;
    last_sign_date)
        last_sign_date
        ;;
    do_sign)
        do_sign
        ;;
    do_sign_async)
        do_sign_async
        ;;
    check_sign_result)
        check_sign_result
        ;;
    list_logs)
        list_logs
        ;;
    read_log)
        read_log "$2" "$3"
        ;;
    delete_log)
        delete_log "$2"
        ;;
    delete_all_logs)
        delete_all_logs
        ;;
    read_email_config)
        read_email_config
        ;;
    save_email_config)
        save_email_config "$2"
        ;;
    save_email_config_file)
        save_email_config_file "$2"
        ;;
    save_email_config_base64)
        save_email_config_base64 "$2"
        ;;
    get_version)
        get_version
        ;;
    read_cron_config)
        read_cron_config
        ;;
    save_cron_config)
        save_cron_config "$2"
        ;;
    save_cron_config_base64)
        save_cron_config_base64 "$2"
        ;;
    test_notification)
        test_notification
        ;;
    test_get_code)
        test_get_code
        ;;
    read_code_api_config)
        read_code_api_config
        ;;
    save_code_api_config)
        save_code_api_config "$2"
        ;;
    save_code_api_config_base64)
        save_code_api_config_base64 "$2"
        ;;
    *)
        echo "用法: $0 {init|check_status|check_status_detail|last_sign_date|do_sign|do_sign_async|check_sign_result|list_logs|read_log|delete_log|delete_all_logs|read_email_config|save_email_config|save_email_config_file|save_email_config_base64|get_version|read_cron_config|save_cron_config|save_cron_config_base64|test_notification|test_get_code|read_code_api_config|save_code_api_config|save_code_api_config_base64}"
        exit 1
        ;;
esac