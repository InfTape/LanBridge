#include "FolderTransfer.h"
#include "CngAlgorithm.h"
#include "CheckedSize.h"
#include <bcrypt.h>
#include <algorithm>
#include <fstream>
#include <stdexcept>
#include <cwctype>

namespace bridge::folders {
namespace {
void Require(bool value, const char* why) { if(!value) throw std::runtime_error(why); }
void Put(Bytes& out, uint64_t n, int count) { for(int i=count-1;i>=0;--i) out.push_back(static_cast<unsigned char>(n>>(i*8))); }
uint64_t Get(const Bytes& in, size_t offset, int count) { Require(count>=0 && offset<=in.size() && static_cast<size_t>(count)<=in.size()-offset,"Truncated folder packet"); uint64_t n=0; for(int i=0;i<count;++i) n=(n<<8)|in[offset+i]; return n; }
void PlainDirectory(const std::filesystem::path& path) {
    const auto attributes=GetFileAttributesW(path.c_str());
    Require(attributes!=INVALID_FILE_ATTRIBUTES && (attributes&FILE_ATTRIBUTE_DIRECTORY) && !(attributes&FILE_ATTRIBUTE_REPARSE_POINT),"Folder or parent is unavailable or is a link");
}
void NoLinkedParents(const std::filesystem::path& path) {
    auto current=path.root_path();
    PlainDirectory(current);
    for(const auto& part:path.relative_path()) { current/=part; PlainDirectory(current); }
}
class Digest {
    cng::Algorithm algorithm_; BCRYPT_HASH_HANDLE hash_=nullptr;
public:
    Digest() {
        BCRYPT_ALG_HANDLE algorithm=nullptr;
        Require(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"SHA256 unavailable");
        algorithm_.reset(algorithm);
        Require(BCryptCreateHash(algorithm_.get(),&hash_,nullptr,0,nullptr,0,0)>=0,"SHA256 unavailable");
    }
    Digest(const Digest&)=delete;
    Digest& operator=(const Digest&)=delete;
    Digest(Digest&&)=delete;
    Digest& operator=(Digest&&)=delete;
    ~Digest() { if(hash_) cng::Cleanup(BCryptDestroyHash(hash_)); }
    void Add(const Bytes& bytes) { Require(BCryptHashData(hash_,const_cast<PUCHAR>(bytes.data()),CheckedSize<ULONG>(bytes.size()),0)>=0,"SHA256 failed"); }
    Bytes Finish() { Bytes out(32); Require(BCryptFinishHash(hash_,out.data(),32,0)>=0,"SHA256 failed"); return out; }
};
struct Handle {
    HANDLE value;
    explicit Handle(HANDLE handle=INVALID_HANDLE_VALUE):value(handle) {}
    Handle(const Handle&)=delete;
    Handle& operator=(const Handle&)=delete;
    Handle(Handle&&)=delete;
    Handle& operator=(Handle&&)=delete;
    ~Handle(){if(value!=INVALID_HANDLE_VALUE && value!=nullptr) CloseHandle(value);}
};
constexpr auto Receipts=L"Software\\LanBridge\\PendingFolders";
void Forget(const std::wstring& name) { HKEY key=nullptr; if(RegOpenKeyExW(HKEY_CURRENT_USER,Receipts,0,KEY_SET_VALUE,&key)==ERROR_SUCCESS) { RegDeleteValueW(key,name.c_str()); RegCloseKey(key); } }
void Remember(const std::filesystem::path& stage) {
    const auto text=stage.wstring(); Bytes bytes(4+(text.size()+1)*sizeof(wchar_t)); const DWORD pid=GetCurrentProcessId(); memcpy(bytes.data(),&pid,4); memcpy(bytes.data()+4,text.c_str(),(text.size()+1)*sizeof(wchar_t));
    HKEY key=nullptr; Require(RegCreateKeyExW(HKEY_CURRENT_USER,Receipts,0,nullptr,0,KEY_SET_VALUE,nullptr,&key,nullptr)==ERROR_SUCCESS,"Cannot record unfinished folder");
    const auto result=RegSetValueExW(key,stage.filename().c_str(),0,REG_BINARY,bytes.data(),static_cast<DWORD>(bytes.size())); RegCloseKey(key); Require(result==ERROR_SUCCESS,"Cannot record unfinished folder");
}
}
void Recover(const std::filesystem::path& destination) {
    std::filesystem::path root;
    try { root=std::filesystem::canonical(destination); NoLinkedParents(root); } catch(...) { return; }
    HKEY key=nullptr; if(RegOpenKeyExW(HKEY_CURRENT_USER,Receipts,0,KEY_QUERY_VALUE|KEY_SET_VALUE,&key)!=ERROR_SUCCESS) return;
    std::vector<std::wstring> remove;
    bool recoveryFailureReported=false;
    for(DWORD index=0;index<1024;++index) {
        wchar_t name[128]{}; DWORD length=128,type=0,size=65536; Bytes bytes(size);
        const auto result=RegEnumValueW(key,index,name,&length,nullptr,&type,bytes.data(),&size); if(result==ERROR_NO_MORE_ITEMS) break;
        if(result!=ERROR_SUCCESS || type!=REG_BINARY || size<8 || (size-4)%sizeof(wchar_t)) continue;
        std::wstring receipt(name,length),prefix=L".lanbridge-";
        if(!receipt.starts_with(prefix) || !receipt.ends_with(L".part") || receipt.size()!=prefix.size()+16+5 || !std::all_of(receipt.begin()+static_cast<std::wstring::difference_type>(prefix.size()),receipt.end()-5,[](wchar_t ch){return (ch>=L'0' && ch<=L'9') || (ch>=L'A' && ch<=L'F');})) continue;
        DWORD owner=0; memcpy(&owner,bytes.data(),4); std::wstring text((size-4)/sizeof(wchar_t),L'\0'); memcpy(text.data(),bytes.data()+4,size-4);
        if(text.back()!=L'\0') continue; text.pop_back(); if(text.find(L'\0')!=std::wstring::npos) continue;
        auto stage=std::filesystem::path(text).lexically_normal();
        if(!stage.is_absolute() || stage.filename()!=receipt || CompareStringOrdinal(stage.parent_path().c_str(),-1,root.c_str(),-1,TRUE)!=CSTR_EQUAL) continue;
        const Handle process{OpenProcess(SYNCHRONIZE,FALSE,owner)};
        if(process.value==nullptr && GetLastError()!=ERROR_INVALID_PARAMETER) continue;
        if(process.value!=nullptr && process.value!=INVALID_HANDLE_VALUE && WaitForSingleObject(process.value,0)==WAIT_TIMEOUT) continue;
        const auto attributes=GetFileAttributesW(stage.c_str());
        if(attributes==INVALID_FILE_ATTRIBUTES) { remove.push_back(receipt); continue; }
        if(!(attributes&FILE_ATTRIBUTE_DIRECTORY) || (attributes&FILE_ATTRIBUTE_REPARSE_POINT)) continue;
        try {
            bool linked=false; for(auto& entry:std::filesystem::recursive_directory_iterator(stage)) if(GetFileAttributesW(entry.path().c_str())&FILE_ATTRIBUTE_REPARSE_POINT) {linked=true; break;}
            if(linked) continue;
            std::filesystem::remove_all(stage); remove.push_back(receipt);
        } catch(...) {
            // Keep the receipt for the next restart; report at most once per pass.
            if(!recoveryFailureReported) OutputDebugStringW(L"LanBridge: folder recovery deferred; pending receipt retained\n");
            recoveryFailureReported=true;
        }
    }
    for(auto& name:remove) RegDeleteValueW(key,name.c_str()); RegCloseKey(key);
}
std::filesystem::path RelativePath(const std::string& text) {
    Require(!text.empty() && text.size()<=MaxPathBytes,"Folder path is empty or too long");
    const auto wide=Wide(text); std::filesystem::path path; size_t start=0, depth=0;
    while(start<=wide.size()) {
        auto end=wide.find(L'/',start); if(end==std::wstring::npos) end=wide.size();
        auto name=wide.substr(start,end-start);
        Require(!name.empty() && name!=L"." && name!=L".." && name.size()<=180 && Utf8(name).size()<=255 && ++depth<=MaxDepth,"Invalid folder path component");
        for(const auto ch:name) Require(ch>=32 && std::wstring(L"<>:\"\\|?*").find(ch)==std::wstring::npos,"Invalid folder path character");
        Require(name.back()!=L'.' && name.back()!=L' ',"Folder path ends with dot or space");
        auto stem=name.substr(0,name.find(L'.')); std::transform(stem.begin(),stem.end(),stem.begin(),::towupper);
        Require(stem!=L"CON" && stem!=L"PRN" && stem!=L"AUX" && stem!=L"NUL" && stem!=L"CONIN$" && stem!=L"CONOUT$" &&
            !(stem.size()==4 && (stem.starts_with(L"COM") || stem.starts_with(L"LPT")) && std::wstring(L"123456789¹²³").find(stem[3])!=std::wstring::npos),"Reserved folder path name");
        path/=name; if(end==wide.size()) break; start=end+1;
    }
    return path;
}
std::wstring PathKey(const std::string& text) {
    const auto path=RelativePath(text).generic_wstring();
    const int count=LCMapStringEx(LOCALE_NAME_INVARIANT,LCMAP_UPPERCASE,path.data(),static_cast<int>(path.size()),nullptr,0,nullptr,nullptr,0);
    Require(count>0,"Cannot normalize path"); std::wstring key(count,L'\0');
    Require(LCMapStringEx(LOCALE_NAME_INVARIANT,LCMAP_UPPERCASE,path.data(),static_cast<int>(path.size()),key.data(),count,nullptr,nullptr,0)==count,"Cannot normalize path"); return key;
}
Plan Collect(const std::filesystem::path& selected) {
    const auto root=std::filesystem::absolute(selected).lexically_normal(); NoLinkedParents(root);
    Plan plan; plan.name=Utf8(root.filename().wstring()); Require(RelativePath(plan.name).has_parent_path()==false,"Invalid root folder name");
    std::set<std::wstring> names;
    for(const auto& entry:std::filesystem::recursive_directory_iterator(root)) {
        Require(plan.entries.size()<MaxEntries,"Folder exceeds 10000 entries");
        const auto attributes=GetFileAttributesW(entry.path().c_str());
        Require(attributes!=INVALID_FILE_ATTRIBUTES && !(attributes&FILE_ATTRIBUTE_REPARSE_POINT),"Folder contains a link or unavailable entry");
        auto relative=Utf8(entry.path().lexically_relative(root).generic_wstring());
        Require(names.insert(PathKey(relative)).second,"Folder contains conflicting path names");
        const bool directory=(attributes&FILE_ATTRIBUTE_DIRECTORY)!=0; const uint64_t size=directory?0:entry.file_size();
        Require(size<=MaxFile && size<=MaxTotal-plan.bytes,"Folder or file exceeds transfer limit");
        plan.entries.push_back({entry.path(),relative,directory,size});
        if(directory) ++plan.directories; else { ++plan.files; plan.bytes+=size; }
    }
    // Parents precede their children, regardless of filesystem enumeration order.
    std::sort(plan.entries.begin(),plan.entries.end(),[](const Entry& a,const Entry& b) { const auto ad=std::count(a.relative.begin(),a.relative.end(),'/'), bd=std::count(b.relative.begin(),b.relative.end(),'/'); if(ad!=bd) return ad<bd; if(a.directory!=b.directory) return a.directory; return a.relative<b.relative; });
    return plan;
}
Bytes EncodeHeader(const Header& h) { const auto name=RelativePath(h.name); Require(!name.has_parent_path() && h.id!=0 && uint64_t(h.files)+h.directories<=MaxEntries && h.bytes<=MaxTotal && (h.files>0 || h.bytes==0),"Invalid folder header"); Bytes b; Put(b,h.id,8); Put(b,h.name.size(),2); b.insert(b.end(),h.name.begin(),h.name.end()); Put(b,h.files,4); Put(b,h.directories,4); Put(b,h.bytes,8); return b; }
Header DecodeHeader(const Bytes& b) {
    const auto n=Get(b,8,2); Require(b.size()==n+26,"Invalid folder header");
    Header h{Get(b,0,8),std::string(reinterpret_cast<const char*>(b.data()+10),CheckedSize<size_t>(n)),static_cast<uint32_t>(Get(b,10+n,4)),static_cast<uint32_t>(Get(b,14+n,4)),Get(b,18+n,8)};
    Require(h.id!=0 && uint64_t(h.files)+h.directories<=MaxEntries && h.bytes<=MaxTotal && (h.files>0 || h.bytes==0),"Invalid folder totals"); Require(!RelativePath(h.name).has_parent_path(),"Invalid root name"); return h;
}
Bytes FileHeader(const std::string& name,uint64_t size) { RelativePath(name); Require(size<=MaxFile,"File exceeds transfer limit"); Bytes b; Put(b,name.size(),2); b.insert(b.end(),name.begin(),name.end()); Put(b,size,8); return b; }
Bytes Reply(uint64_t id,bool accepted,const std::string& reason) { Bytes b; Put(b,id,8); b.push_back(accepted?1:0); b.insert(b.end(),reason.begin(),reason.end()); return b; }
uint64_t Id(const Bytes& b) { return Get(b,0,8); }
bool Accepted(const Bytes& b) { Require(b.size()>=9 && b.size()<=4096 && b[8]<=1,"Invalid folder reply"); return b[8]==1; }
std::string Reason(const Bytes& b) { Accepted(b); auto text=std::string(b.begin()+9,b.end()); Wide(text); return text; }
struct Receiver::File { Handle handle; uint64_t size=0,done=0; Digest hash; };
Receiver::Receiver(const std::filesystem::path& destination,const Header& header):header_(header),destination_(std::filesystem::canonical(destination)) {
    EncodeHeader(header_);
    NoLinkedParents(destination_); Require(std::filesystem::space(destination_).available>header.bytes+1024ULL*1024,"Not enough disk space for folder");
    staging_=destination_/(L".lanbridge-"+Wide(RandomCode())+L".part"); Require(CreateDirectoryW(staging_.c_str(),nullptr)!=FALSE,"Cannot create folder staging directory");
    try { Remember(staging_); receipt_=staging_.filename().wstring(); } catch(...) { RemoveDirectoryW(staging_.c_str()); throw; }
}
Receiver::~Receiver() { file_.reset(); if(!staging_.empty()) { std::error_code error; std::filesystem::remove_all(staging_,error); if(!error) Forget(receipt_); } }
void Receiver::Register(const std::string& text,bool directory) {
    const auto path=RelativePath(text); const auto key=PathKey(text);
    const auto parent=path.parent_path().generic_wstring();
    Require(directories_.contains(parent.empty()?L"":PathKey(Utf8(parent))),"Missing parent directory declaration");
    Require(names_.insert(key).second,"Duplicate or conflicting folder entry");
    if(directory) { Require(dirsDone_<header_.directories,"Too many directories"); directories_.insert(key); ++dirsDone_; }
    else Require(filesDone_<header_.files,"Too many files");
}
void Receiver::Consume(unsigned char type,const Bytes& b) {
    if(type==10) {
        Require(!file_,"Directory during file transfer"); const std::string name(b.begin(),b.end()); Register(name,true);
        NoLinkedParents(staging_/RelativePath(name).parent_path()); Require(CreateDirectoryW((staging_/RelativePath(name)).c_str(),nullptr)!=FALSE,"Cannot create received directory");
    } else if(type==11) {
        Require(!file_ && b.size()>=10,"Unexpected folder file header"); const auto n=Get(b,0,2); Require(b.size()==n+10,"Invalid folder file header");
        const auto name=std::string(reinterpret_cast<const char*>(b.data()+2),CheckedSize<size_t>(n)); const auto size=Get(b,2+n,8);
        Require(size<=MaxFile && size<=header_.bytes-done_,"Invalid folder file size"); Register(name,false); NoLinkedParents(staging_/RelativePath(name).parent_path());
        file_=std::make_unique<File>(); file_->size=size;
        file_->handle.value=CreateFileW((staging_/RelativePath(name)).c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        Require(file_->handle.value!=INVALID_HANDLE_VALUE,"Cannot create folder file");
    } else if(type==4) {
        Require(file_ && !b.empty() && b.size()<=file_->size-file_->done,"Invalid folder file chunk"); DWORD written=0;
        Require(WriteFile(file_->handle.value,b.data(),static_cast<DWORD>(b.size()),&written,nullptr) && written==b.size(),"Cannot write folder file");
        file_->hash.Add(b); file_->done+=b.size(); done_+=b.size();
    } else if(type==5) {
        Require(file_ && b.size()==32 && file_->done==file_->size && file_->hash.Finish()==b,"Folder file SHA-256 verification failed");
        Require(FlushFileBuffers(file_->handle.value)!=FALSE,"Cannot flush folder file"); file_.reset(); ++filesDone_;
    } else throw std::runtime_error("Unexpected folder packet");
}
std::filesystem::path Receiver::Commit() {
    Require(!file_ && filesDone_==header_.files && dirsDone_==header_.directories && done_==header_.bytes,"Incomplete folder transfer"); NoLinkedParents(destination_);
    const auto base=Wide(header_.name);
    for(int i=0;i<=10000;++i) {
        auto suffix=i?L" ("+std::to_wstring(i)+L")":L""; auto prefix=base;
        while(prefix.size()+suffix.size()>180 || Utf8(prefix+suffix).size()>255) { prefix.pop_back(); if(!prefix.empty() && prefix.back()>=0xD800 && prefix.back()<=0xDBFF) prefix.pop_back(); }
        auto final=destination_/(prefix+suffix);
        if(MoveFileExW(staging_.c_str(),final.c_str(),MOVEFILE_WRITE_THROUGH)) { Forget(receipt_); staging_.clear(); return final; }
        const auto error=GetLastError(); Require(error==ERROR_ALREADY_EXISTS || error==ERROR_FILE_EXISTS || (error==ERROR_ACCESS_DENIED && GetFileAttributesW(final.c_str())!=INVALID_FILE_ATTRIBUTES),"Cannot publish received folder");
    }
    throw std::runtime_error("Too many folder name collisions");
}
void Send(Channel& c,const Plan& plan,const std::function<void(uint64_t,uint64_t)>& progress) {
    uint64_t totalDone=0; progress(0,plan.bytes);
    for(const auto& entry:plan.entries) {
        NoLinkedParents(entry.directory?entry.source:entry.source.parent_path());
        if(entry.directory) { c.Send(10,Bytes(entry.relative.begin(),entry.relative.end())); continue; }
        const Handle file{CreateFileW(entry.source.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,nullptr)};
        Require(file.value!=INVALID_HANDLE_VALUE,"Cannot open folder file"); BY_HANDLE_FILE_INFORMATION info{}; LARGE_INTEGER size{};
        Require(GetFileInformationByHandle(file.value,&info) && !(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)) && GetFileSizeEx(file.value,&size) && std::cmp_equal(size.QuadPart,entry.size),"Folder file changed or is a link");
        c.Send(11,FileHeader(entry.relative,entry.size)); Digest digest; uint64_t done=0;
        while(done<entry.size) {
            Bytes chunk(static_cast<size_t>(std::min<uint64_t>(262144,entry.size-done))); DWORD read=0;
            Require(ReadFile(file.value,chunk.data(),static_cast<DWORD>(chunk.size()),&read,nullptr) && read==chunk.size(),"Folder file changed or read failed");
            digest.Add(chunk); c.Send(4,chunk); done+=read; totalDone+=read; progress(totalDone,plan.bytes);
        }
        c.Send(5,digest.Finish());
    }
}
}
