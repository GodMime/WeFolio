package com.jxc.wefolio.service.portfolioai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jxc.wefolio.common.auth.AuthContextHolder;
import com.jxc.wefolio.config.PortfolioAiProperties;
import com.jxc.wefolio.dict.PortfolioOwnerTypeDict;
import com.jxc.wefolio.dict.AiTaskStatusDict;
import com.jxc.wefolio.dict.PortfolioTemplateTypeDict;
import com.jxc.wefolio.dict.PortfolioStatusDict;
import com.jxc.wefolio.dict.TeamRoleDict;
import com.jxc.wefolio.dict.UserStatusDict;
import com.jxc.wefolio.entity.PortfolioEntity;
import com.jxc.wefolio.entity.UserEntity;
import com.jxc.wefolio.exception.BusinessException;
import com.jxc.wefolio.mapper.PortfolioEntityMapper;
import com.jxc.wefolio.mapper.UserEntityMapper;
import com.jxc.wefolio.message.PortfolioAiReferenceMessage;
import com.jxc.wefolio.service.CosService;
import com.jxc.wefolio.service.teamportfolio.TeamPortfolioAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import static com.jxc.wefolio.service.portfolioai.PortfolioAiReferenceProtocol.*;

/** 无台账参考附件：可信回执、当前权限、引用保护与不可变事实集中在此处理。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioAiReferenceService {
    /** 项目统一时间与签名用途。 */
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final String HMAC="HmacSHA256", USER=PortfolioOwnerTypeDict.USER.getCode(), TEAM=PortfolioOwnerTypeDict.TEAM.getCode();
    /** 团队参考附件只有维护者可读。 */
    private static final Set<String> ROLES=Set.of(TeamRoleDict.OWNER.getCode(),TeamRoleDict.MANAGER.getCode());
    /** 活动态与终态延续同一保护窗口。 */
    private static final Set<String> ACTIVE=Set.of(AiTaskStatusDict.PENDING.getCode(),AiTaskStatusDict.RUNNING.getCode()),
            TERMINAL=Set.of(AiTaskStatusDict.SUCCEEDED.getCode(),AiTaskStatusDict.FAILED.getCode(),AiTaskStatusDict.CANCELLED.getCode());
    /** 新接口封闭请求字段。 */
    private static final Set<String> TICKET_KEYS=Set.of(OWNER_TYPE,OWNER_ID,PORTFOLIO_ID,NAME,MIME,SIZE);
    /** 媒体识别由客户端完成，确认请求必须完整提交媒体信息。 */
    private static final String MEDIA_INFO="mediaInfo";
    private static final Set<String> COMPLETE_KEYS=Set.of(UPLOAD_TOKEN,MEDIA_INFO);
    /** 附件开关与稳定签名密钥。 */
    private final PortfolioAiProperties properties;
    /** 精确本桶读取与写入票据。 */
    private final CosService cos;
    /** 持锁的远端请求必须使用独立短超时客户端。 */
    private final PortfolioAiReferenceStorage storage;
    /** 现有用户和作品集身份。 */
    private final UserEntityMapper users;
    private final PortfolioEntityMapper portfolios;
    /** 现有团队角色门禁，避免循环依赖作品集保存用例。 */
    private final TeamPortfolioAccessService teamAccess;
    /** 独立列引用查询，不使用客户端 JSON 作为归属来源。 */
    private final JdbcTemplate jdbc;
    /** 与 Job 共用的 owner 命名锁。 */
    private final PortfolioAiReferenceLock lock;
    /** 复用缓存与既有分布式锁实现的每请求人签票限流。 */
    private final PortfolioAiReferenceUploadQuota quota;

    /** 新上传签票只返回精确对象票据，不产生数据库记录。 */
    public Map<String,Object> uploadTicket(String raw) {
        if(!properties.isReferenceEnabled()) throw new BusinessException(PortfolioAiReferenceMessage.DISABLED);
        ObjectNode request=request(raw,TICKET_KEYS); String type=text(request,OWNER_TYPE); long owner=id(request,OWNER_ID);
        String code=requireOwner(type,owner);
        if(request.hasNonNull(PORTFOLIO_ID)) requirePortfolio(type,owner,id(request,PORTFOLIO_ID));
        String mime=text(request,MIME),name=filename(text(request,NAME));
        long maximum=maxBytes(mime);
        if(!request.path(SIZE).isIntegralNumber() || !request.path(SIZE).canConvertToLong() || request.path(SIZE).asLong()<=0 || request.path(SIZE).asLong()>maximum) throw invalid();
        quota.consume(AuthContextHolder.requireUserId());
        String attachmentId=UUID.randomUUID().toString().replace("-","");
        String key=code+PREFIX+attachmentId+"."+EXTENSIONS.get(mime);
        ObjectNode grant=NODES.objectNode().put(VERSION,1).put(PURPOSE,UPLOAD).put(OWNER_TYPE,type).put(OWNER_ID,owner)
                .put(KEY,key).put(ID,attachmentId).put(NAME,name).put(MIME,mime).put(SIZE,request.path(SIZE).asLong())
                .put(EXPIRES,System.currentTimeMillis()+WINDOW_MS);
        String token=sign(grant);
        storage.ensureImmutable();
        CosService.PostUploadTicket ticket=cos.createPrivatePostUploadTicket(key,mime,maximum,LocalDateTime.now(ZONE).plusMinutes(15),ZONE);
        return Map.of(UPLOAD_TOKEN,token,"uploadUrl",ticket.uploadUrl(),"formData",ticket.formData(),ID,attachmentId);
    }

    /** 信任客户端媒体信息，仅核对对象身份与权限；不下载附件，重复确认不延长原件截止。 */
    public Map<String,Object> complete(String raw) {
        ObjectNode request=request(raw,COMPLETE_KEYS); ObjectNode grant=verify(text(request,UPLOAD_TOKEN),UPLOAD);
        String type=text(grant,OWNER_TYPE); long owner=id(grant,OWNER_ID); String code=requireOwner(type,owner);
        if(grant.path(EXPIRES).asLong()<=System.currentTimeMillis()) throw new BusinessException(PortfolioAiReferenceMessage.EXPIRED);
        String key=text(grant,KEY),mime=text(grant,MIME); requireKey(code,grant.path(ID).asText(),key,mime);
        CosService.VersionedObjectHead before=head(key); validateHead(before,mime);
        // 签票大小必须存在，把客户端声明绑定到同一不可变对象，不接受旧票据缺省。
        if(!grant.path(SIZE).isIntegralNumber() || !grant.path(SIZE).canConvertToLong()
                || grant.path(SIZE).asLong()!=before.contentLength()) throw invalid();
        ObjectNode item=PortfolioAiReferenceMediaInfo.facts(request.get(MEDIA_INFO),mime,before.contentLength());
        item.put(ID,text(grant,ID)).put(KEY,key).put(NAME,filename(text(grant,NAME)));
        item.putObject(CONTENT).put(ETAG,before.eTag()).put(MODIFIED,before.lastModified());
        return withOwnerLock(type,owner,()->{
            if(grant.path(EXPIRES).asLong()<=System.currentTimeMillis()) throw new BusinessException(PortfolioAiReferenceMessage.EXPIRED);
            requireOwner(type,owner); checkObject(item);
            if(!protectedAt(item,references(type,owner),System.currentTimeMillis())) throw new BusinessException(PortfolioAiReferenceMessage.EXPIRED);
            return display(item,type,owner,true);
        });
    }

    /** 预览签名只对当前维护权和仍受保护的不可变对象签发。 */
    public Map<String,Object> previewTicket(String raw) {
        ObjectNode request=request(raw,Set.of(OWNER_TYPE,OWNER_ID,ID,TOKEN)); String type=text(request,OWNER_TYPE); long owner=id(request,OWNER_ID);
        String code=requireOwner(type,owner); ObjectNode item=fromToken(text(request,TOKEN),type,owner,text(request,ID));
        requireKey(code,text(item,ID),text(item,KEY),text(item,MIME)); checkObject(item);
        if(!protectedAt(item,references(type,owner),System.currentTimeMillis())) throw new BusinessException(PortfolioAiReferenceMessage.EXPIRED);
        return Map.of("url",cos.signedGet(text(item,KEY),Duration.ofMinutes(5)));
    }

    /** 由最终保存/提交事务的外围包装调用；不把下载或模型调用放进临界区。 */
    public <T>T withOwnerLock(String type,long owner,Supplier<T> action) { return lock.execute(type,owner,action); }

    /** 最终保存事务解析可信清单并保留编号高水位。 */
    public String resolveForSave(String type,long owner,ArrayNode values,String previousJson,Integer nextNo) {
        ObjectNode previous=portfolioStored(previousJson); ObjectNode resolved=resolve(type,owner,values,previous);
        int next=previous.path(NEXT).asInt(1);
        for(JsonNode item:resolved.path(ITEMS)) next=Math.max(next,number(item)+1);
        if(nextNo!=null) { if(nextNo<next || nextNo>1_000_000_000) throw invalid(); next=nextNo; }
        return resolved.put(NEXT,next).toString();
    }

    /** 新任务冻结本次输入；空数组沿用原任务无附件列。 */
    public String resolveForTask(String type,long owner,ArrayNode values,String previousJson) {
        if(values==null || values.isEmpty()) return null;
        if(!properties.isReferenceEnabled()) throw new BusinessException(PortfolioAiReferenceMessage.DISABLED);
        return resolve(type,owner,values,portfolioStored(previousJson)).toString();
    }

    /** 锁内只校验轻量事实；请求里的大小、路径、版本从不被采信。 */
    private ObjectNode resolve(String type,long owner,ArrayNode values,ObjectNode previous) {
        lock.checkHeld(); String code=requireOwner(type,owner); ArrayNode business=normalizeBusinessProjection(values);
        Map<String,ObjectNode> protectedItems=references(type,owner), old=new HashMap<>();
        for(JsonNode item:previous.path(ITEMS)) old.put(text(item,ID),(ObjectNode)item);
        ArrayNode result=NODES.arrayNode(); int videos=0;
        for(int index=0;index<business.size();index++) {
            ObjectNode desired=(ObjectNode)business.get(index); String attachmentId=text(desired,ID);
            ObjectNode item;
            if(values.get(index).has(TOKEN)) item=fromToken(text(values.get(index),TOKEN),type,owner,attachmentId);
            else item=protectedItems.values().stream().filter(value->attachmentId.equals(value.path(ID).asText())).findFirst()
                    .map(ObjectNode::deepCopy).orElseThrow(()->new BusinessException(PortfolioAiReferenceMessage.UNAVAILABLE));
            requireKey(code,attachmentId,text(item,KEY),text(item,MIME));
            if(!protectedAt(item,protectedItems,System.currentTimeMillis())) throw new BusinessException(PortfolioAiReferenceMessage.EXPIRED);
            checkObject(item);
            ObjectNode prior=old.get(attachmentId);
            if(prior!=null && !prior.path(NUMBER).equals(desired.path(NUMBER)) || prior==null && number(desired)<previous.path(NEXT).asInt(1)) throw invalid();
            item.set(NUMBER,desired.path(NUMBER)); item.set(NOTE,desired.path(NOTE));
            if(VIDEO.equals(item.path(MEDIA).asText()) && ++videos>1) throw invalid();
            result.add(item);
        }
        lock.checkBeforeCommit(); ObjectNode envelope=NODES.objectNode().put(VERSION,1); envelope.set(ITEMS,result);
        stored(envelope.toString()); return envelope;
    }

    /** 幂等比较只读取规范化业务字段，不受临时凭证刷新/过期影响。 */
    public static ArrayNode normalizeBusinessProjection(JsonNode items) { return business(items); }
    /** 从已冻结列中构建同样的业务投影。 */
    public static ArrayNode businessProjection(String json) {
        ArrayNode values=NODES.arrayNode();
        for(JsonNode item:stored(json).path(ITEMS)) values.addObject().setAll(Map.of(ID,item.path(ID),NUMBER,item.path(NUMBER),NOTE,item.path(NOTE)));
        return business(values);
    }

    /** 仅高级维护详情调用；公开响应不携带对象键或内容版本。 */
    public Map<String,Object> maintenance(String json,String type,long owner) {
        ObjectNode envelope=portfolioStored(json);
        return Map.of("aiReferenceAttachments",taskResponse(json,type,owner),"aiReferenceNextNo",envelope.path(NEXT).asInt(1));
    }
    /** 历史候选仍可查看；只有明确失效才返回不可用，临时读取失败保留客户端重试语义。 */
    public List<Map<String,Object>> taskResponse(String json,String type,long owner) {
        requireOwner(type,owner); List<Map<String,Object>> result=new ArrayList<>();
        ObjectNode envelope=stored(json);
        if(envelope.path(ITEMS).isEmpty()) return result;
        ReferenceSnapshot snapshot;
        try { snapshot=readReferences(type,owner,true); }
        catch(RuntimeException exception) {
            log.warn("参考附件引用读取暂时失败: ownerType={}, ownerId={}, errorType={}",type,owner,exception.getClass().getSimpleName());
            throw new BusinessException(PortfolioAiReferenceMessage.STORAGE);
        }
        for(JsonNode value:envelope.path(ITEMS)) {
            ObjectNode item=(ObjectNode)value;
            boolean available=protectedAt(item,snapshot.items,System.currentTimeMillis());
            if(snapshot.conflictingKeys.contains(text(item,KEY)) || !available && !snapshot.complete) {
                throw new BusinessException(PortfolioAiReferenceMessage.STORAGE);
            }
            if(available) {
                try { checkObject(item); }
                catch(BusinessException exception) {
                    if(!PortfolioAiReferenceMessage.UNAVAILABLE.equals(exception.getMessage())) throw exception;
                    available=false;
                }
            }
            result.add(display(item,type,owner,available));
        }
        return result;
    }

    /** 保存/删除已提交后的最佳努力清理，失败不改变成功的业务响应。 */
    public void cleanupRemoved(String type,long owner,String oldJson,String newJson) {
        try {
            Set<String> retained=new HashSet<>();
            for(JsonNode item:stored(newJson).path(ITEMS)) retained.add(text(item,KEY));
            for(JsonNode item:stored(oldJson).path(ITEMS)) if(!retained.contains(text(item,KEY))) {
                withOwnerLock(type,owner,()->{cleanupOne(type,owner,(ObjectNode)item);return null;});
            }
        } catch(RuntimeException exception) { log.warn("参考附件即时清理延后: ownerType={}, ownerId={}, errorType={}",type,owner,exception.getClass().getSimpleName()); }
    }
    /** 同锁复核引用后删除单个已知对象；失败对象仍由 COS 枚举补偿。 */
    private void cleanupOne(String type,long owner,ObjectNode item) {
        requireKey(requireOwner(type,owner),text(item,ID),text(item,KEY),text(item,MIME));
        lock.checkHeld(); Map<String,ObjectNode> protectedItems=references(type,owner);
        CosService.VersionedObjectHead head=storage.head(text(item,KEY));
        if(head==null || head.lastModified()+WINDOW_MS>System.currentTimeMillis() || protectedItems.containsKey(text(item,KEY))) return;
        lock.checkHeld(); storage.delete(text(item,KEY));
    }

    /** 能力只列产品规格，不包含内部凭证或存储配置。 */
    public Map<String,Object> capabilities() {
        Map<String,Object> values=new LinkedHashMap<>(); values.put("enabled",properties.isReferenceEnabled());
        values.put("maxCount",MAX_COUNT);values.put("maxVideos",1);values.put("imageMaxBytes",IMAGE_BYTES);values.put("imageMaxEdge",MAX_EDGE);
        values.put("videoMaxBytes",VIDEO_BYTES);values.put("videoMaxDurationSeconds",MAX_SECONDS);values.put("sourceVideoMaxBytes",500_000_000L);return values;
    }

    /** 保存与删除需要完整可信的保护集合，坏行不得解释成没有引用。 */
    private Map<String,ObjectNode> references(String type,long owner) {
        return readReferences(type,owner,false).items;
    }

    /** 只读展示隔离无关坏行；未被确认保护的对象在扫描不完整时仍须返回重试错误。 */
    private ReferenceSnapshot readReferences(String type,long owner,boolean tolerateInvalidRows) {
        ReferenceSnapshot snapshot=new ReferenceSnapshot(tolerateInvalidRows);
        List<String> portfolioRows=jdbc.query("SELECT ai_reference_attachments_json FROM wf_portfolio WHERE owner_type=? AND owner_id=? AND deleted=0 AND ai_reference_attachments_json IS NOT NULL",
                (rs,row)->rs.getString(1),type,owner);
        for(String json:portfolioRows) readReferenceRow(snapshot,()->addReferences(snapshot,portfolioStored(json)));
        jdbc.query("SELECT reference_attachments_json,status,completed_at,deleted FROM wf_ai_generation_task WHERE owner_type=? AND owner_id=? AND reference_attachments_json IS NOT NULL",
                rs->{
                    String status=rs.getString(2),json=rs.getString(1); LocalDateTime completed=rs.getObject(3,LocalDateTime.class);
                    long deleted=rs.getLong(4);
                    readReferenceRow(snapshot,()->{
                        if(status==null || !ACTIVE.contains(status) && !TERMINAL.contains(status)) throw invalid();
                        if(TERMINAL.contains(status) && completed==null) throw invalid();
                        boolean protects=ACTIVE.contains(status) && deleted==0 || TERMINAL.contains(status)
                                && completed.atZone(ZONE).toInstant().toEpochMilli()+WINDOW_MS>System.currentTimeMillis();
                        ObjectNode envelope=stored(json);
                        if(envelope.has(NEXT)) throw invalid();
                        if(protects) addReferences(snapshot,envelope);
                    });
                },type,owner);
        if(!snapshot.complete) log.warn("参考附件引用存在异常行，展示保留不确定状态: ownerType={}, ownerId={}",type,owner);
        return snapshot;
    }

    /** 只隔离数据协议错误；数据库连接错误仍使整个查询失败，不能误报过期。 */
    private void readReferenceRow(ReferenceSnapshot snapshot,Runnable read) {
        try { read.run(); }
        catch(BusinessException exception) {
            if(!snapshot.tolerateInvalidRows) throw exception;
            snapshot.complete=false;
        }
    }

    /** 有效引用可共享同一原件；内容版本冲突只阻断该对象的展示，不影响其它确定引用。 */
    private void addReferences(ReferenceSnapshot snapshot,ObjectNode envelope) {
        for(JsonNode item:envelope.path(ITEMS)) {
            String key=text(item,KEY);
            if(snapshot.conflictingKeys.contains(key)) continue;
            ObjectNode previous=snapshot.items.putIfAbsent(key,(ObjectNode)item);
            if(previous!=null && !previous.path(CONTENT).equals(item.path(CONTENT))) {
                if(!snapshot.tolerateInvalidRows) throw invalid();
                snapshot.items.remove(key); snapshot.conflictingKeys.add(key); snapshot.complete=false;
            }
        }
    }

    /** 一次引用扫描的确定保护与不确定性，禁止将坏行造成的缺失解释为过期。 */
    private static final class ReferenceSnapshot {
        /** 已完整校验且没有版本冲突的保护引用。 */
        private final Map<String,ObjectNode> items=new HashMap<>();
        /** 存在版本冲突的对象需要重试或修复数据，不能重新签发回执。 */
        private final Set<String> conflictingKeys=new HashSet<>();
        /** 仅只读展示允许隔离坏行，保存和删除必须失败关闭。 */
        private final boolean tolerateInvalidRows;
        /** 无坏行时才能认定一个对象确实没有任何保护引用。 */
        private boolean complete=true;
        /** 为当前读取用途设置容错边界。 */
        private ReferenceSnapshot(boolean tolerateInvalidRows) {this.tolerateInvalidRows=tolerateInvalidRows;}
    }
    /** 上传缓冲与有效数据库引用共同决定保留，更新回执不延长缓冲。 */
    private boolean protectedAt(ObjectNode item,Map<String,ObjectNode> references,long now) {
        return item.path(CONTENT).path(MODIFIED).asLong()+WINDOW_MS>now || references.containsKey(text(item,KEY));
    }
    /** 当前维护权限与真实唯一码，不能信任前端目录。 */
    private String requireOwner(String type,long owner) {
        long actor=AuthContextHolder.requireUserId();
        if(USER.equals(type)) {
            UserEntity user=users.selectById(owner);
            if(owner!=actor || user==null || !UserStatusDict.ACTIVE.getCode().equals(user.getStatus()) || user.getUniqueCode()==null) throw new BusinessException(PortfolioAiReferenceMessage.UNAVAILABLE);
            return safeCode(user.getUniqueCode());
        }
        if(TEAM.equals(type)) return safeCode(teamAccess.requireTeamRole(owner,actor,ROLES).team().getUniqueCode());
        throw invalid();
    }
    /** 可选作品集必须属于当前 owner 的高级维护入口。 */
    private void requirePortfolio(String type,long owner,long portfolioId) {
        PortfolioEntity portfolio=portfolios.selectById(portfolioId);
        if(portfolio==null || !type.equals(portfolio.getOwnerType()) || !Objects.equals(owner,portfolio.getOwnerId())
                || !PortfolioTemplateTypeDict.ADVANCED.getCode().equals(portfolio.getTemplateType())
                || !PortfolioStatusDict.ACTIVE.getCode().equals(portfolio.getStatus())
                || portfolio.getDeleted()!=null && portfolio.getDeleted()!=0L) throw new BusinessException(PortfolioAiReferenceMessage.UNAVAILABLE);
    }
    /** 签名后的文件事实仍必须与当前 COS 内容一致。 */
    private void checkObject(ObjectNode item) {
        validateFacts(item); CosService.VersionedObjectHead current=head(text(item,KEY));
        if(current.contentLength()!=item.path(SIZE).asLong() || !Objects.equals(current.eTag(),item.path(CONTENT).path(ETAG).asText())
                || current.lastModified()!=item.path(CONTENT).path(MODIFIED).asLong()) throw new BusinessException(PortfolioAiReferenceMessage.UNAVAILABLE);
    }
    /** HEAD 404 与服务故障使用受控错误，不泄漏对象身份。 */
    private CosService.VersionedObjectHead head(String key) {
        try { CosService.VersionedObjectHead value=storage.head(key); if(value==null) throw new BusinessException(PortfolioAiReferenceMessage.UNAVAILABLE); return value; }
        catch(BusinessException exception) { throw exception; } catch(RuntimeException exception) { throw new BusinessException(PortfolioAiReferenceMessage.STORAGE); }
    }
    /** HEAD 仅核对对象大小与版本存在性，不读取或识别文件内容。 */
    private void validateHead(CosService.VersionedObjectHead value,String mime) {
        if(value.contentLength()<=0 || value.contentLength()>maxBytes(mime) || value.eTag()==null || value.lastModified()<=0) throw invalid();
    }
    /** key 必须精确匹配当前 owner 与不可变 UUID。 */
    private void requireKey(String code,String attachmentId,String key,String mime) {
        if(!attachmentId.matches("[0-9a-f]{32}") || !EXTENSIONS.containsKey(mime) || !Objects.equals(code+PREFIX+attachmentId+"."+EXTENSIONS.get(mime),key)) throw invalid();
    }
    /** 展示信息不返回内部对象 key、版本或上传 token。 */
    private Map<String,Object> display(ObjectNode item,String type,long owner,boolean available) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:List.of(ID,NUMBER,NOTE,MEDIA,NAME,MIME,SIZE,DURATION,WIDTH,HEIGHT)) if(item.has(field)) {
            JsonNode value=item.path(field); result.put(field,value.isNull()?null:value.isNumber()?value.numberValue():value.asText());
        }
        result.put("available",available);
        result.put(EXPIRES,item.path(CONTENT).path(MODIFIED).asLong()+WINDOW_MS);
        if(available) {
            ObjectNode token=item.deepCopy(); token.remove(List.of(NUMBER,NOTE)); token.put(VERSION,1).put(PURPOSE,ATTACHMENT).put(OWNER_TYPE,type).put(OWNER_ID,owner);
            try { result.put(TOKEN,sign(token)); }
            catch(BusinessException exception) { result.put("available",false); }
        }
        return result;
    }
    /** 签名回执去除授权包装后返回可持久化文件事实。 */
    private ObjectNode fromToken(String token,String type,long owner,String attachmentId) {
        ObjectNode item=verify(token,ATTACHMENT);
        if(!type.equals(text(item,OWNER_TYPE)) || owner!=id(item,OWNER_ID) || !attachmentId.equals(text(item,ID))) throw invalid();
        item.remove(List.of(VERSION,PURPOSE,OWNER_TYPE,OWNER_ID)); validateFacts(item); return item;
    }
    /** 签名载荷使用 URL 安全编码，签名覆盖原始字节并使用固定用途。 */
    private String sign(ObjectNode payload) {
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(encoded));
    }
    /** 先进行常量时间验签，再解析受限 JSON，拒绝两种用途互换。 */
    private ObjectNode verify(String token,String purpose) {
        try {
            if(token.length()>MAX_BYTES) throw invalid(); String[] parts=token.split("\\.",-1);
            if(parts.length!=2 || !MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1]))) throw invalid();
            ObjectNode payload=StrictAiJson.parseObject(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8),MAX_BYTES,8);
            if(!payload.path(VERSION).isIntegralNumber() || !payload.path(VERSION).canConvertToInt() || payload.path(VERSION).asInt()!=1 || !purpose.equals(text(payload,PURPOSE))) throw invalid(); return payload;
        } catch(BusinessException exception) { throw exception; } catch(RuntimeException exception) { throw invalid(); }
    }
    /** 稳定密钥必须来自部署配置，不能在进程启动时随机生成。 */
    private byte[] mac(String value) {
        String secret=properties.getReferenceTokenSecret();
        if(secret==null || secret.getBytes(StandardCharsets.UTF_8).length<32) throw new BusinessException(PortfolioAiReferenceMessage.DISABLED);
        try { Mac mac=Mac.getInstance(HMAC);mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),HMAC));return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)); }
        catch(Exception exception) { throw new BusinessException(PortfolioAiReferenceMessage.DISABLED); }
    }
    /** 封闭请求阻止隐式忽略客户端文件事实。 */
    private ObjectNode request(String raw,Set<String> allowed) {
        try { ObjectNode node=StrictAiJson.parseObject(raw,MAX_BYTES,8);node.fieldNames().forEachRemaining(field->{if(!allowed.contains(field)) throw invalid();});return node; }
        catch(BusinessException exception) { throw exception; } catch(RuntimeException exception) { throw invalid(); }
    }
    /** ID 兼容已有小程序使用的十进制字符串和整数。 */
    private long id(JsonNode node,String field) {
        try { JsonNode value=node.path(field); if(!value.isTextual()&&!value.isIntegralNumber())throw invalid();long id=Long.parseLong(value.asText());if(id<=0)throw invalid();return id; }
        catch(NumberFormatException exception) { throw invalid(); }
    }
    /** 数量限制前已验证编号格式，因此转换不会溢出。 */
    private int number(JsonNode item) { return Integer.parseInt(item.path(NUMBER).asText().substring(1)); }
    /** 清除文件路径和控制字符，长度以 Unicode 码点计。 */
    private String filename(String source) {
        String clean=source.replace('\\','/'); clean=clean.substring(clean.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").trim();
        if(clean.isBlank() || clean.codePointCount(0,clean.length())>200) throw invalid(); return clean;
    }
    /** 白名单格式的产品字节限制。 */
    private long maxBytes(String mime) { if(!EXTENSIONS.containsKey(mime)) throw invalid();return "video/mp4".equals(mime)?VIDEO_BYTES:IMAGE_BYTES; }
    /** 作品集清单必须保存高水位，缺失不能被悄悄降为一。 */
    private ObjectNode portfolioStored(String json) {ObjectNode value=stored(json);if(json!=null&&!value.has(NEXT))throw invalid();return value;}
    /** 数据库唯一码也必须满足目录白名单，损坏值不得进入 COS key。 */
    private String safeCode(String code) {if(code==null||!code.matches("[A-Za-z0-9]+"))throw invalid();return code;}
}
